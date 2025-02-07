package org.constellation.storage

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.constellation.storage.algebra.StorageAlgebra
import org.constellation.util.Metrics

import scala.collection.immutable.Queue
import scala.concurrent.duration._

class StorageService[F[_]: Concurrent, V](
  metricName: Option[String] = None,
  expireAfter: Option[FiniteDuration] = None
) extends StorageAlgebra[F, String, V] {

  private val lruCache: Cache[String, V] = {
    val cacheWithStats = metricName.fold(Scaffeine())(_ => Scaffeine().recordStats())

    expireAfter
      .fold(cacheWithStats)(cacheWithStats.expireAfterAccess)
      .build[String, V]()
  }

  private val queueRef: Ref[F, Queue[V]] = Ref.unsafe[F, Queue[V]](Queue[V]())
  private val maxQueueSize = 20

  if (metricName.isDefined) {
    Metrics.cacheMetrics.addCache(metricName.get, lruCache.underlying)
  }

  def update(key: String, updateFunc: V => V, empty: => V): F[V] =
    lookup(key)
      .map(_.map(updateFunc).getOrElse(empty))
      .flatMap(v => putToCache(key, v))

  def update(key: String, updateFunc: V => V): F[Option[V]] =
    lookup(key)
      .flatMap(
        _.map(updateFunc)
          .traverse(v => putToCache(key, v))
      )

  private[storage] def putToCache(key: String, v: V): F[V] =
    Sync[F].delay(lruCache.put(key, v)).map(_ => v)

  // TODO: Check if we need queue for transactions as putAll does not touch queue
  def putAll(kv: Map[String, V]): F[Map[String, V]] = putAllToCache(kv)

  private[storage] def putAllToCache(kv: Map[String, V]): F[Map[String, V]] =
    Sync[F].delay(lruCache.putAll(kv)).map(_ => kv)

  def put(key: String, v: V): F[V] =
    queueRef.modify {
      case q if q.size >= maxQueueSize => (q.dequeue._2, ())
      case q                           => (q, ())
    } >>
      queueRef.modify(q => (q.enqueue(v), ())) >>
      putToCache(key, v)

  def lookup(key: String): F[Option[V]] =
    Sync[F].delay(lruCache.getIfPresent(key))

  def remove(keys: Set[String]): F[Unit] =
    Sync[F].delay(lruCache.invalidateAll(keys))

  def contains(key: String): F[Boolean] =
    lookup(key).map(_.isDefined)

  def size(): F[Long] = Sync[F].delay(lruCache.estimatedSize())

  def count(predicate: V => Boolean): F[Long] = toMap().map(_.count { case (_, v) => predicate(v) }.toLong)

  def toMap(): F[Map[String, V]] =
    Sync[F].delay(lruCache.asMap().toMap)

  def getLast20(): F[List[V]] =
    queueRef.get.map(_.reverse.toList)

  def clear: F[Unit] =
    queueRef
      .modify(_ => (Queue[V](), ()))
      .flatTap(_ => Sync[F].delay(lruCache.invalidateAll()))
}
