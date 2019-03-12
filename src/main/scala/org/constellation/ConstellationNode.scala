package org.constellation

import java.net.InetSocketAddress
import java.security.KeyPair
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LoggingMagnet}
import akka.http.scaladsl.server.{Directive0, Route}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import better.files._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.{Logger, StrictLogging}
import constellation._
import org.constellation.CustomDirectives.printResponseTime
import org.constellation.consensus.{HTTPNodeRemoteSender, CrossTalkConsensus, NodeRemoteSender}
import org.constellation.crypto.KeyUtils
import org.constellation.datastore.SnapshotTrigger
import org.constellation.datastore.swaydb.SwayDBDatastore
import org.constellation.p2p.PeerAPI
import org.constellation.primitives.Schema.{NodeState, ValidPeerIPData}
import org.constellation.primitives._
import org.constellation.util.{APIClient, Metrics}
import org.json4s.BuildInfo

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// scopt requires default args for all properties.
// Make sure to check for null early -- don't propogate nulls anywhere else.
case class CliConfig(externalIp: java.net.InetAddress = null,
                     externalPort: Int = 0,
                     debug: Boolean = false)

object ConstellationNode extends StrictLogging {

  //noinspection ScalaStyle
  def main(args: Array[String]): Unit = {
    logger.info("Main init")

    import scopt.OParser
    val builder = OParser.builder[CliConfig]
    val parser1 = {
      import builder._
      OParser.sequence(
        programName("constellation"),
        head("constellation", BuildInfo.version),
        opt[java.net.InetAddress]("ip")
          .action((x, c) => c.copy(externalIp = x))
          .valueName("<ip address>")
          .text("the ip you can be reached from outside"),
        opt[Int]('p', "port")
          .action((x, c) => c.copy(externalPort = x))
          .text("the port you can be reached from outside"),
        opt[Int]('d', "debug")
          .action((x, c) => c.copy(externalPort = x))
          .text("run the node in debug mode"),
        help("help").text("prints this usage text"),
        version("version").text(s"Constellation v${BuildInfo.version}"),
        checkConfig(
          c =>
            if (c.externalIp == null ^ c.externalPort == 0) {
              failure("ip and port must either both be set, or neither.")
            } else success
        )
      )
    }

    // OParser.parse returns Option[Config]
    val cliConfig: CliConfig = OParser.parse(parser1, args, CliConfig()) match {
      case Some(c) => c
      case _       =>
        // arguments are bad, error message will have been displayed
        throw new RuntimeException("Invalid set of cli options")
    }

    val config = ConfigFactory.load()
    logger.info("Config loaded")

//    logger.info(s"external ip:port = ${conf.externalIp}:${conf.externalPort}")

    Try {

      implicit val system: ActorSystem = ActorSystem("Constellation")
      implicit val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext: ExecutionContext = system.dispatchers.lookup("main-dispatcher")

      val rpcTimeout = config.getInt("rpc.timeout")

      // TODO: Move to scopt above.
      val seeds: Seq[HostPort] =
        if (config.hasPath("seedPeers")) {
          import scala.collection.JavaConverters._
          val peersList = config.getStringList("seedPeers")
          peersList.asScala
            .map(_.split(":"))
            .map(arr => HostPort(arr(0), arr(1).toInt))
        } else Seq()

      logger.info(s"Seeds: $seeds")

      val requestExternalAddressCheck = false
      /*
          val seeds = args.headOption.map(_.split(",").map{constellation.addressToSocket}.toSeq).getOrElse(Seq())

          val hostName = if (args.length > 1) {
            args(1)
          } else "127.0.0.1"

          val requestExternalAddressCheck = if (args.length > 2) {
            args(2).toBoolean
          } else false
       */

      val preferencesPath = File(".dag")
      preferencesPath.createDirectoryIfNotExists()

      val _ = KeyUtils.provider // Ensure initialized

      val hostName = Option(cliConfig.externalIp).map(_.toString).getOrElse {
        Try { File("external_host_ip").lines.mkString }.getOrElse("127.0.0.1")
      }

      // TODO: update to take from config
      val keyPairFile = File(".dag/key")
      val keyPair: KeyPair =
        if (keyPairFile.notExists) {
          logger.warn(
            s"Key pair not found in $keyPairFile - Generating new key pair"
          )
          val kp = KeyUtils.makeKeyPair()
          keyPairFile.write(kp.json)
          kp
        } else {

          try {
            keyPairFile.lines.mkString.x[KeyPair]
          } catch {
            case e: Exception =>
              logger.error(
                s"Keypair stored in $keyPairFile is invalid. Please delete it and rerun to create a new one.",
                e
              )
              throw e
          }
        }

      val portOffset = Option(cliConfig.externalPort).filter(_ != 0)
      val httpPortFromArg = portOffset.map { _ + 1 }
      val peerHttpPortFromArg = portOffset.map { _ + 2 }

      val httpPort = httpPortFromArg.getOrElse(
        Option(System.getenv("DAG_HTTP_PORT"))
          .map {
            _.toInt
          }
          .getOrElse(config.getInt("http.port"))
      )

      val peerHttpPort = peerHttpPortFromArg.getOrElse(
        Option(System.getenv("DAG_PEER_HTTP_PORT"))
          .map {
            _.toInt
          }
          .getOrElse(9001)
      )

      val node = new ConstellationNode(
        keyPair,
        seeds,
        config.getString("http.interface"),
        httpPort,
        config.getString("udp.interface"),
        config.getInt("udp.port"),
        timeoutSeconds = rpcTimeout,
        hostName = hostName,
        requestExternalAddressCheck = requestExternalAddressCheck,
        peerHttpPort = peerHttpPort,
        attemptDownload = true,
        allowLocalhostPeers = false,
        nodeConfig = NodeConfig()
      )
    } match {
      case Failure(e) => e.printStackTrace()
      case Success(_) =>
        logger.info("success")

        while (true) {
          Thread.sleep(60 * 1000)
        }

    }

  }

}

case class NodeConfig(
  metricIntervalSeconds: Int = 60,
  isGenesisNode: Boolean = false
)

class ConstellationNode(val configKeyPair: KeyPair,
                        val seedPeers: Seq[HostPort],
                        val httpInterface: String,
                        val httpPort: Int,
                        val udpInterface: String = "0.0.0.0",
                        val udpPort: Int = 16180,
                        val hostName: String = "127.0.0.1",
                        val timeoutSeconds: Int = 10,
                        val requestExternalAddressCheck: Boolean = false,
                        val autoSetExternalAddress: Boolean = false,
                        val peerHttpPort: Int = 9001,
                        val peerTCPPort: Int = 9002,
                        val attemptDownload: Boolean = false,
                        val allowLocalhostPeers: Boolean = false,
                        nodeConfig: NodeConfig = NodeConfig())(
  implicit val system: ActorSystem,
  implicit val materialize: ActorMaterializer,
  implicit val executionContext: ExecutionContext
) {

  implicit val dao: DAO = new DAO(nodeConfig)
  dao.updateKeyPair(configKeyPair)
  dao.idDir.createDirectoryIfNotExists(createParents = true)

  dao.preventLocalhostAsPeer = !allowLocalhostPeers
  dao.externalHostString = hostName
  dao.externlPeerHTTPPort = peerHttpPort

  import dao._

  val metrics_ = new Metrics(periodSeconds = dao.processingConfig.metricCheckInterval)
  dao.metrics = metrics_


  val snapshotTrigger = new SnapshotTrigger()

  val ipManager = IPManager()

  dao.transactionHashStore = SwayDBDatastore.duplicateCheckStore(dao, "transaction_hash_store")
  dao.checkpointHashStore = SwayDBDatastore.duplicateCheckStore(dao, "checkpoint_hash_store")

  dao.actorMaterializer = materialize

  val logger = Logger(s"ConstellationNode_$publicKeyHash")

  logger.info(s"Node init with API $httpInterface $httpPort peerPort: $peerHttpPort")

  constellation.standardTimeout = Timeout(timeoutSeconds, TimeUnit.SECONDS)

  val udpAddressString: String = hostName + ":" + udpPort
  lazy val peerHostPort = HostPort(hostName, peerHttpPort)
  val udpAddress = new InetSocketAddress(hostName, udpPort)

  if (autoSetExternalAddress) {
    dao.externalAddress = Some(udpAddress)
    dao.apiAddress = Some(new InetSocketAddress(hostName, httpPort))
    dao.tcpAddress = Some(new InetSocketAddress(hostName, peerTCPPort))
  }

  val peerManager: ActorRef = system.actorOf(
    Props(new PeerManager(ipManager)),
    s"PeerManager_$publicKeyHash"
  )

  // val dbActor = SwayDBDatastore(dao)

//  dao.dbActor = dbActor
  dao.peerManager = peerManager

  private val logReqResp: Directive0 = DebuggingDirectives.logRequestResult(
    LoggingMagnet(printResponseTime(logger))
  )

  // If we are exposing rpc then create routes
  val routes: Route = new API(udpAddress).routes // logReqResp { }

  logger.info("API Binding")

  // Setup http server for internal API
  private val bindingFuture: Future[Http.ServerBinding] =
    Http().bindAndHandle(routes, httpInterface, httpPort)

  val remoteSenderActor: ActorRef = system.actorOf(NodeRemoteSender.props(new HTTPNodeRemoteSender))
  val crossTalkConsensusActor: ActorRef = system.actorOf(CrossTalkConsensus.props(remoteSenderActor))
  val peerAPI = new PeerAPI(ipManager, crossTalkConsensusActor)
  val randomTXManager = new RandomTransactionManager(crossTalkConsensusActor)

  val peerRoutes: Route = peerAPI.routes // logReqResp { }

  /*  seedPeers.foreach {
    peer => ipManager.addKnownIP(RemoteAddress(peer))
  }*/

  def addAddressToKnownIPs(addr: ValidPeerIPData): Unit = {
    val remoteAddr = RemoteAddress(new InetSocketAddress(addr.canonicalHostName, addr.port))
    ipManager.addKnownIP(remoteAddr)
  }

  def getIPData: ValidPeerIPData = {
    ValidPeerIPData(this.hostName, this.peerHttpPort)
  }

  def getInetSocketAddress: InetSocketAddress = {
    new InetSocketAddress(this.hostName, this.peerHttpPort)
  }

  // Setup http server for peer API
  private val peerBindingFuture = Http().bindAndHandle(peerRoutes, httpInterface, peerHttpPort)

  def shutdown(): Unit = {

    bindingFuture
      .foreach(_.unbind())

    peerBindingFuture
      .foreach(_.unbind())
    // TODO: we should add this back but it currently causes issues in the integration test
    //.onComplete(_ => system.terminate())

    //TypedActor(system).stop(dbActor)
  }

  //////////////

  // TODO : Move to separate test class - these are within jvm only but won't hurt anything
  // We could also consider creating a 'Remote Proxy class' that represents a foreign
  // ConstellationNode (i.e. the current Peer class) and have them under a common interface

  def getAPIClient(host: String = hostName,
                   port: Int = httpPort,
                   udpPort: Int = udpPort): APIClient = {
    val api = APIClient(host, port, udpPort)
    api.id = id
    api
  }

  def getAddPeerRequest: PeerMetadata = {
    PeerMetadata(hostName, udpPort, peerHttpPort, dao.id)
  }

  def getAPIClientForNode(node: ConstellationNode): APIClient = {
    val ipData = node.getIPData
    val api = APIClient(host = ipData.canonicalHostName, port = ipData.port)
    api.id = id
    api
  }

  logger.info("Node started")

  if (attemptDownload) {
    seedPeers.foreach {
      dao.peerManager ! _
    }
    PeerManager.initiatePeerReload()(dao, dao.edgeExecutionContext)
  }

  if (nodeConfig.isGenesisNode) {
    logger.info("Creating genesis block")
    Genesis.start()
    logger.info(s"Genesis block hash ${dao.genesisBlock.map { _.soeHash }.getOrElse("")}")
    dao.setNodeState(NodeState.Ready)
    dao.generateRandomTX = true
  }

}
