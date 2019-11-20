package org.constellation.primitives

import java.security.KeyPair

import cats.effect.{IO, _}
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.constellation.DAO
import org.constellation.keytool.KeyUtils
import org.constellation.primitives.Schema._
import org.constellation.schema.Id
import org.constellation.util.AccountBalance

object Genesis extends StrictLogging {

  implicit val unsafeLogger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  final val Coinbase = "coinbase"
  // TODO: That key should be provided externally by the genesis creator.
  // We can use Node's KeyPair as well.
  final val CoinbaseKey = KeyUtils.makeKeyPair()

  private final val GenesisTips = Seq(
    TypedEdgeHash(Coinbase, EdgeHashType.CheckpointHash),
    TypedEdgeHash(Coinbase, EdgeHashType.CheckpointHash)
  )

  def createDistributionTransactions[F[_]: Sync](
    allocAccountBalances: Seq[AccountBalance]
  )(implicit dao: DAO): F[Seq[Transaction]] =
    allocAccountBalances.toList
      .traverse(ab => dao.transactionService.createTransaction(Coinbase, ab.accountHash, ab.balance, CoinbaseKey))
      .asInstanceOf[F[Seq[Transaction]]]

  def createGenesisBlock(transactions: Seq[Transaction]): CheckpointBlock =
    CheckpointBlock.createCheckpointBlock(transactions, GenesisTips)(CoinbaseKey)

  def createDistributionBlock(genesisSOE: SignedObservationEdge): CheckpointBlock =
    CheckpointBlock.createCheckpointBlock(
      Seq.empty,
      Seq(
        TypedEdgeHash(genesisSOE.hash, EdgeHashType.CheckpointHash, Some(genesisSOE.baseHash)),
        TypedEdgeHash(genesisSOE.hash, EdgeHashType.CheckpointHash, Some(genesisSOE.baseHash))
      )
    )(CoinbaseKey)

  def createGenesisObservation(
    allocAccountBalances: Seq[AccountBalance] = Seq.empty
  )(implicit dao: DAO): GenesisObservation = {
    // TODO: Get rid of unsafeRunSync
    val txs = createDistributionTransactions[IO](allocAccountBalances).unsafeRunSync()
    val genesis = createGenesisBlock(txs)
    GenesisObservation(
      genesis,
      createDistributionBlock(genesis.soe),
      createDistributionBlock(genesis.soe)
    )
  }

  def start()(implicit dao: DAO): Unit = {
    val genesisObservation = createGenesisObservation(dao.nodeConfig.allocAccountBalances)
    acceptGenesis(genesisObservation)
  }

  /**
    * Build genesis tips and example distribution among initial nodes
    *
    * @param ids: Initial node public keys
    * @return : Resolved edges for state update
    */
  // TODO: Get rid of this after fixing unit tests
  def createGenesisAndInitialDistributionDirect(
    selfAddressStr: String,
    ids: Set[Id],
    keyPair: KeyPair,
    allocAccountBalances: Seq[AccountBalance] = Seq.empty
  )(implicit dao: DAO): GenesisObservation =
    createGenesisObservation()

  // TODO: Get rid of this after fixing unit tests
  def createGenesisAndInitialDistribution(
    selfAddressStr: String,
    allocAccountBalances: Seq[AccountBalance] = Seq.empty,
    keyPair: KeyPair
  )(
    implicit dao: DAO
  ): GenesisObservation = createGenesisObservation(allocAccountBalances)

  // TODO: Make async
  def acceptGenesis(go: GenesisObservation, setAsTips: Boolean = true)(implicit dao: DAO): Unit = {
    // Store hashes for the edges

    (go.genesis.storeSOE() >>
      go.initialDistribution.storeSOE() >>
      go.initialDistribution2.storeSOE() >>
      IO(go.genesis.store(CheckpointCache(Some(go.genesis), height = Some(Height(0, 0))))) >>
      IO(go.initialDistribution.store(CheckpointCache(Some(go.initialDistribution), height = Some(Height(1, 1))))) >>
      IO(go.initialDistribution2.store(CheckpointCache(Some(go.initialDistribution2), height = Some(Height(1, 1))))))
    // TODO: Get rid of unsafeRunSync
      .unsafeRunSync()

    go.genesis.transactions.foreach { rtx =>
      val bal = rtx.amount
      dao.addressService
        .putUnsafe(rtx.dst.hash, AddressCacheData(bal, bal, Some(1000d), balanceByLatestSnapshot = bal))
        // TODO: Get rid of unsafeRunSync
        .unsafeRunSync()
    }

    dao.genesisObservation = Some(go)
    dao.genesisBlock = Some(go.genesis)

    /*
    // Dumb way to set these as active tips, won't pass a double validation but no big deal.
    checkpointMemPool(go.initialDistribution.baseHash) = go.initialDistribution
    checkpointMemPool(go.initialDistribution2.baseHash) = go.initialDistribution2
    checkpointMemPoolThresholdMet(go.initialDistribution.baseHash) = go.initialDistribution -> 0
    checkpointMemPoolThresholdMet(go.initialDistribution2.baseHash) = go.initialDistribution2 -> 0
     */

    dao.metrics.updateMetric("genesisAccepted", "true")

    if (setAsTips) {
      List(go.initialDistribution, go.initialDistribution2)
        .map(dao.concurrentTipService.update(_, Height(1, 1), isGenesis = true))
        .sequence
        .unsafeRunSync() // TODO: Get rid of unsafeRunSync
    }
    storeTransactions(go)

    dao.metrics.updateMetric("genesisHash", go.genesis.soeHash)

    // TODO: Get rid of unsafeRunSync
    dao.genesisObservationWriter
      .write(go)
      .fold(
        err => logger.error(s"Cannot write genesis observation ${err.exceptionMessage}"),
        _ => logger.debug("Genesis observation saved successfully")
      )
      .unsafeRunSync()
  }

  private def storeTransactions(genesisObservation: GenesisObservation)(implicit dao: DAO): Unit =
    Seq(genesisObservation.genesis, genesisObservation.initialDistribution, genesisObservation.initialDistribution2).flatMap {
      cb =>
        cb.transactions
          .map(tx => TransactionCacheData(transaction = tx, cbBaseHash = Some(cb.baseHash)))
          .map(tcd => dao.transactionService.accept(tcd))
    }.toList.sequence.void
    // TODO: Get rid of unsafeRunSync
      .unsafeRunSync()
}
