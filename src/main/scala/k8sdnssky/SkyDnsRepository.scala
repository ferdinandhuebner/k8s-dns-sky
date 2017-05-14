package k8sdnssky
import java.util.concurrent.TimeUnit

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import k8sdnssky.SkyDnsRepository._
import mousio.etcd4j.EtcdClient
import mousio.etcd4j.responses.{EtcdErrorCode, EtcdException, EtcdKeysResponse}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

object SkyDnsRepository {
  object Protocol {
  }
  sealed trait FailureKind
  case class InvalidModel(hostname: String, record: String) extends FailureKind
  case class RecordInUse(hostname: String, record: String, inUseBy: String) extends FailureKind
  object UnableToDelete extends FailureKind
  object UnableToPut extends FailureKind

  class SkyDnsException(message: String, cause: Throwable, failureKind: FailureKind)
      extends Exception(message, cause) {

    val kind: FailureKind = failureKind
  }
}

case class SkyDnsModel(
    @JsonProperty("host") host: String,
    @JsonProperty("origin") origin: String,
    @JsonProperty("group") group: String)

class SkyDnsRepository(
    private val etcd: EtcdClient,
    private val etcdMaxTimeout: FiniteDuration,
    private val skydnsKeyPrefix: String,
    private val masterUrl: String) {

  private def toKey(hostname: String): String = {
    skydnsKeyPrefix + hostname.split("\\.").reverse.mkString("/")
  }

  private val mapper: ObjectMapper = new ObjectMapper() with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  private val log = LoggerFactory.getLogger(getClass)

  private def lookup(response: EtcdKeysResponse): Try[List[SkyDnsModel]] = {
    if (response == null || response.getNode == null) {
      Success(Nil)
    } else {
      if (response.getNode.isDir) {
        val nodes = response.getNode.getNodes
        if (nodes == null || nodes.isEmpty) {
          Success(Nil)
        } else {
          import scala.collection.JavaConverters._
          Success(nodes.asScala.map(node => {
            try {
              mapper.readValue(node.getValue, classOf[SkyDnsModel])
            } catch {
              case e: Exception =>
                log.warn(s"Unable to parse node ${node.key}", e)
                null
            }
          }).filter(_ != null).toList)
        }
      } else {
        Failure(new IllegalStateException("Found value node instead of directory"))
      }
    }
  }

  private def doPut(hostname: String, records: List[String], resourceLink: String, ttl: Int): PutResponse = {
    val newRecords: mutable.Buffer[String] = mutable.Buffer()
    val failures: mutable.Buffer[SkyDnsException] = mutable.Buffer()

    records.foreach(record => {
      val key = s"${toKey(hostname)}/record-${record.replaceAll("\\.", "_")}"
      val origin = s"$masterUrl$resourceLink"
      val group = s"$hostname@$origin"
      val json = mapper.writeValueAsString(SkyDnsModel(record, origin, group))
      log.trace(s"Marshalled JSON: $json")
      try {
        etcd.put(key, json).ttl(ttl).timeout(etcdMaxTimeout.toMillis, TimeUnit.MILLISECONDS).send().get()
        newRecords += record
      } catch {
        case e: Exception => failures += new SkyDnsException("Unable to put", e, UnableToPut)
      }
    })
    PutResponse(Nil, newRecords.toList, Nil, failures.toList)
  }

  private def deleteIfEmpty(key: String): Unit = {
    try {
      val response = etcd.get(key).send().get()
      if (!response.getNode.getNodes.isEmpty) {
        import scala.collection.JavaConverters._
        response.getNode.getNodes.asScala.foreach(node => {
          if (node.isDir) {
            deleteIfEmpty(node.key)
          }
        })
      } else {
        if (response.getNode.isDir) {
          etcd.deleteDir(key).send().get()
        }
      }
    } catch {
      case e: EtcdException if e.errorCode == EtcdErrorCode.KeyNotFound =>
        // no point in logging it; the effect is as desired (directory deleted from etcd)
        // log.warn(s"key $key not found while deleting record")
      case e: Exception => log.warn(s"Unable to delete $key", e)
    }
  }

  def delete(hostname: String, records: List[String]): Map[String, SkyDnsException] = {
    val failures: mutable.Map[String, SkyDnsException] = mutable.Map()
    records.foreach(record => {
      val key = s"${toKey(hostname)}/record-${record.replaceAll("\\.", "_")}"
      try {
        etcd.delete(key).timeout(etcdMaxTimeout.toMillis, TimeUnit.MILLISECONDS).send().get()
      } catch {
        case e: EtcdException if e.errorCode == EtcdErrorCode.KeyNotFound =>
          // no point in logging it; the effect is as desired (key deleted from etcd)
          // log.warn(s"key $key not found while deleting record")
        case e: Exception =>
          failures += record -> new SkyDnsException(s"Unable to delete $record", e, UnableToDelete)
      }
    })
    deleteIfEmpty(toKey(hostname))
    failures.toMap
  }

  case class PutResponse(
      refreshedRecords: List[String],
      newRecords: List[String],
      deletedRecords: List[String],
      failures: List[SkyDnsException]
  )

  def put(hostname: String, records: List[String], resourceLink: String, ttl: Int): PutResponse = {
    Try(etcd.get(toKey(hostname)).timeout(etcdMaxTimeout.toMillis, TimeUnit.MILLISECONDS).send().get()) match {
      case Success(response: EtcdKeysResponse) =>
        lookup(response) match {
          case Success(model) if model.isEmpty =>
            doPut(hostname, records, resourceLink, ttl)
          case Success(model) =>
            val origins = model.map(_.origin).distinct
            val expectedOrigin = s"$masterUrl$resourceLink"
            if (origins.length != 1 || origins.head != expectedOrigin) {
              val e: SkyDnsException = if (origins.isEmpty) {
                new SkyDnsException("Invalid model found for record", null, InvalidModel(hostname, null))
              } else {
                new SkyDnsException("Record in use", null, RecordInUse(hostname, null, origins.mkString(", ")))
              }
              PutResponse(Nil, Nil, Nil, List(e))
            } else {
              val existingRecords = model.map(_.host)
              val toAdd = records.diff(existingRecords)
              val toDelete = existingRecords.diff(records)
              val toUpdate = records.filter(x => existingRecords.contains(x))

              val added = doPut(hostname, toAdd, resourceLink, ttl)
              val updated = doPut(hostname, toUpdate, resourceLink, ttl)
              val deleteResult = delete(hostname, toDelete)

              PutResponse(
                updated.newRecords,
                added.newRecords,
                toDelete.filter(x => !deleteResult.contains(x)),
                added.failures ++ updated.failures ++ deleteResult.values
              )
            }
          case Failure(e) =>
            PutResponse(Nil, Nil, Nil, List(new SkyDnsException("Unable to lookup", e, InvalidModel(hostname, null))))
        }
      case Failure(e: EtcdException) if e.errorCode == EtcdErrorCode.KeyNotFound =>
        doPut(hostname, records, resourceLink, ttl)
      case Failure(e) =>
        PutResponse(Nil, Nil, Nil, List(new SkyDnsException("etcd failure", e, UnableToPut)))
    }
  }
}
