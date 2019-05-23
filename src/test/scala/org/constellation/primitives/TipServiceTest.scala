package org.constellation.primitives
import cats.effect.{ContextShift, IO}
import cats.implicits._
import org.constellation.DAO
import org.constellation.consensus.TipData
import org.constellation.primitives.storage.{
  AcceptedTransactionService,
  CheckpointBlocksMemPool,
  CheckpointService
}
import org.constellation.util.Metrics
import org.mockito.integrations.scalatest.IdiomaticMockitoFixture
import org.scalatest.{FunSpecLike, Matchers}

class TipServiceTest extends FunSpecLike with IdiomaticMockitoFixture with Matchers {

  implicit val dao: DAO = prepareDAO()
  implicit val ioContextShift: ContextShift[IO] =
    IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)

  def prepareDAO(): DAO = {
    val dao = mock[DAO]
    dao.metrics shouldReturn mock[Metrics]
    dao.threadSafeSnapshotService shouldReturn mock[ThreadSafeSnapshotService]
    dao.checkpointService shouldReturn mock[CheckpointService]
    dao.checkpointService.memPool shouldReturn mock[CheckpointBlocksMemPool]
    dao.checkpointService.memPool.cacheSize() shouldReturn 0
    dao.acceptedTransactionService shouldReturn mock[AcceptedTransactionService]
    dao.threadSafeSnapshotService.acceptedCBSinceSnapshot shouldReturn Seq.empty
    dao
  }

  describe("TrieBasedTipService") {

    it("limits maximum number of tips") {
      val limit = 6
      val concurrentTipService = new TrieBasedTipService(limit, 10, 2, 30)

      val cbs = createIndexedCBmocks(limit * 3, { i =>
        createCBMock(i.toString)
      })

      val tasks = createShiftedTasks(cbs.toList, { cb =>
        concurrentTipService.update(cb)
      })
      tasks.par.foreach(_.unsafeRunAsyncAndForget)
      Thread.sleep(2000)
      concurrentTipService.toMap.size shouldBe limit
    }

    it("forbids adding conflicting tip") {
      dao.acceptedTransactionService.contains(*) shouldReturn IO.pure(true)
      val limit = 6
      val concurrentTipService = new TrieBasedTipService(limit, 10, 2, 30)
      val txs = prepareTransactions()

      val cbs = createIndexedCBmocks(limit * 3, { i =>
        val cb = createCBMock(i.toString)
        cb.transactions shouldReturn txs
        cb
      })

      val tasks = createShiftedTasks(cbs.toList, { cb =>
        concurrentTipService.update(cb)
      })

      val cbsNotInSnap = cbs.map(_.baseHash)
      dao.threadSafeSnapshotService.acceptedCBSinceSnapshot shouldReturn cbsNotInSnap

      tasks.par.foreach(_.unsafeRunAsyncAndForget)
      Thread.sleep(2000)
      concurrentTipService.toMap.size shouldBe 0
    }
  }

  private def prepareTransactions(): Seq[Transaction] = {
    val tx1 = mock[Transaction]
    tx1.hash shouldReturn "tx1"
    val tx2 = mock[Transaction]
    tx2.hash shouldReturn "tx2"
    Seq(tx1, tx2)
  }

  private def createCBMock(hash: String) = {
    val cb = mock[CheckpointBlock]
    cb.parentSOEBaseHashes shouldReturn Seq.empty
    cb.transactions shouldReturn Seq.empty
    cb.baseHash shouldReturn hash
    cb
  }

  def createIndexedCBmocks(size: Int, func: Int => CheckpointBlock) = {
    (1 to size).map(func)
  }

  def createShiftedTasks(
    cbs: List[CheckpointBlock],
    func: CheckpointBlock => IO[Either[TipConflictException, Option[TipData]]]
  ) =
    cbs.map(IO.shift *> func(_))

}
