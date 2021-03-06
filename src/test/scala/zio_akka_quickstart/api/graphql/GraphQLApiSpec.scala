package zio_akka_quickstart.api.graphql

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import zio_akka_quickstart.application.ApplicationService
import zio_akka_quickstart.domain.{ InMemoryItemRepository, Item, ItemId }
import zio_akka_quickstart.infrastructure.InMemoryEventSubscriber
import zio_akka_quickstart.interop.akka.ZioRouteTest
import zio.json._
import zio.json.ast.Json
import zio._
import zio.blocking._
import zio.clock.Clock
import zio.logging.Logging
import zio.test.Assertion._
import zio.test._
import zio.test.TestAspect.sequential
import de.heikoseeberger.akkahttpziojson.ZioJsonSupport
import GraphQLApiSpecOperation._

object GraphQLApiSpecOperation {
  case class AllItems(allItems: List[Item])
  case class ItemByName(itemByName: List[Item])
  case class CheaperThan(cheaperThan: List[Item])
}

trait GraphQLApiSpecJsonProtocol extends ZioJsonSupport {
  implicit val itemIdDecoder: JsonDecoder[ItemId]           = JsonDecoder[Long].map(ItemId)
  implicit val itemDecoder: JsonDecoder[Item]               = DeriveJsonDecoder.gen[Item]
  implicit val itemsDecoder: JsonDecoder[AllItems]          = DeriveJsonDecoder.gen[AllItems]
  implicit val itemsByNameDecoder: JsonDecoder[ItemByName]  = DeriveJsonDecoder.gen[ItemByName]
  implicit val cheaperThanDecoder: JsonDecoder[CheaperThan] = DeriveJsonDecoder.gen[CheaperThan]
}

object GraphQLApiSpec extends ZioRouteTest with GraphQLApiSpecJsonProtocol {
  def spec =
    (suite("GraphQLApi must")(
      testM("Add item on call to 'addItem'") {
        val query =
          """
            |{
            |	"query": "mutation($name: String!, $price: BigDecimal!) { addItem(name: $name, price: $price) }",
            | "variables": {
            |   "name": "Test item",
            |   "price": 10
            | }
            |}
            |""".stripMargin

        sendQueryAndCheckResult(query) { (statusCode, contentType, body) =>
          val itemId =
            for {
              jsonData     <- body.fields.toMap.get("data")
              jsObjectData <- jsonData.as[Json.Obj].toOption
              jsonAddItem  <- jsObjectData.fields.toMap.get("addItem")
              id           <- jsonAddItem.as[BigDecimal].toOption
            } yield id
          assert(statusCode)(equalTo(StatusCodes.OK)) &&
          assert(contentType)(equalTo(ContentTypes.`application/json`)) &&
          assert(itemId)(isSome(equalTo(BigDecimal(0))))
        }
      },
      testM("Return all items on call to 'allItems'") {
        val query =
          """
            |{
            |	"query": "{ allItems { id name price } }"
            |}
            |""".stripMargin
        sendQueryAndCheckResult(query) { (statusCode, contentType, body) =>
          val allItems = getOperationTypeFromBody[AllItems](body).map(_.allItems)
          assert(statusCode)(equalTo(StatusCodes.OK)) &&
          assert(contentType)(equalTo(ContentTypes.`application/json`)) &&
          assert(allItems)(isSome(equalTo(List(Item(ItemId(0), "Test item", 10)))))
        }
      },
      testM("Return an item, given its ItemId, on call to 'item'") {
        val query =
          """
            |{
            |	"query": "query($itemId: Long!) { item(value: $itemId) { id name price } }",
            | "variables": { "itemId": 0 }
            |}
            |""".stripMargin

        sendQueryAndCheckResult(query) { (statusCode, contentType, body) =>
          val item =
            for {
              jsonData     <- body.fields.toMap.get("data")
              jsObjectData <- jsonData.as[Json.Obj].toOption
              jsonItem     <- jsObjectData.fields.toMap.get("item")
              item         <- jsonItem.as[Item].toOption
            } yield item
          assert(statusCode)(equalTo(StatusCodes.OK)) &&
          assert(contentType)(equalTo(ContentTypes.`application/json`)) &&
          assert(item)(isSome(equalTo(Item(ItemId(0), "Test item", 10))))
        }
      },
      testM("Return null on call to 'item', for an itemId that does not exist") {
        val query =
          """
            |{
            |	"query": "query($itemId: Long!) { item(value: $itemId) { id name price } }",
            | "variables": { "itemId": 1 }
            |}
            |""".stripMargin

        sendQueryAndCheckResult(query) { (statusCode, contentType, body) =>
          val jsonItem =
            for {
              jsonData     <- body.fields.toMap.get("data")
              jsObjectData <- jsonData.as[Json.Obj].toOption
              jsonItem     <- jsObjectData.fields.toMap.get("item")
            } yield jsonItem
          assert(statusCode)(equalTo(StatusCodes.OK)) &&
          assert(contentType)(equalTo(ContentTypes.`application/json`)) &&
          assert(jsonItem)(isSome(equalTo(Json.Null)))
        }
      },
      testM("Return all items with the given name, on call to 'itemByName'") {
        val query =
          """
            |{
            |	"query": "query ($name: String!) { itemByName(name: $name) { id name price } }",
            | "variables": { "name": "Test item" }
            |}
            |""".stripMargin

        sendQueryAndCheckResult(query) { (statusCode, contentType, body) =>
          val items = getOperationTypeFromBody[ItemByName](body).map(_.itemByName)
          assert(statusCode)(equalTo(StatusCodes.OK)) &&
          assert(contentType)(equalTo(ContentTypes.`application/json`)) &&
          assert(items)(isSome(equalTo(List(Item(ItemId(0), "Test item", 10)))))
        }
      },
      testM("Return an empty list on call to 'itemByName', if there are no items with the given name") {
        val query =
          """
            |{
            |	"query": "query ($name: String!) { itemByName(name: $name) { id name price } }",
            | "variables": { "name": "Another item" }
            |}
            |""".stripMargin

        sendQueryAndCheckResult(query) { (statusCode, contentType, body) =>
          val items = getOperationTypeFromBody[ItemByName](body).map(_.itemByName)
          assert(statusCode)(equalTo(StatusCodes.OK)) &&
          assert(contentType)(equalTo(ContentTypes.`application/json`)) &&
          assert(items)(isSome(isEmpty))
        }
      },
      testM("Return all items cheaper than the given price, on call to 'cheaperThan'") {
        val query =
          """
            |{
            |	"query": "query ($price: BigDecimal!) { cheaperThan(price: $price) { id name price } }",
            | "variables": { "price": 100 }
            |}
            |""".stripMargin

        sendQueryAndCheckResult(query) { (statusCode, contentType, body) =>
          val items = getOperationTypeFromBody[CheaperThan](body).map(_.cheaperThan)
          assert(statusCode)(equalTo(StatusCodes.OK)) &&
          assert(contentType)(equalTo(ContentTypes.`application/json`)) &&
          assert(items)(isSome(equalTo(List(Item(ItemId(0), "Test item", 10)))))
        }
      },
      testM("Return an empty list on call to 'cheaperThan', if there are no items cheaper than the given price") {
        val query =
          """
            |{
            |	"query": "query ($price: BigDecimal!) { cheaperThan(price: $price) { id name price } }",
            | "variables": { "price": 5 }
            |}
            |""".stripMargin

        sendQueryAndCheckResult(query) { (statusCode, contentType, body) =>
          val items = getOperationTypeFromBody[CheaperThan](body).map(_.cheaperThan)
          assert(statusCode)(equalTo(StatusCodes.OK)) &&
          assert(contentType)(equalTo(ContentTypes.`application/json`)) &&
          assert(items)(isSome(isEmpty))
        }
      },
      testM("Delete item on call to 'deleteItem'") {
        val query =
          """
            |{
            |	"query": "mutation($itemId: Long!) { deleteItem(value: $itemId) }",
            | "variables": { "itemId": 0 }
            |}
            |""".stripMargin

        sendQueryAndCheckResult(query) { (statusCode, contentType, body) =>
          val jsObjectDeleteItemKeys = getKeysFromBody(body, "deleteItem")
          assert(statusCode)(equalTo(StatusCodes.OK)) &&
          assert(contentType)(equalTo(ContentTypes.`application/json`)) &&
          assert(jsObjectDeleteItemKeys)(isNone)
        }
      },
      testM("Succeed on call to 'deleteItem', when the given itemId does not exist") {
        val query =
          """
            |{
            |	"query": "mutation($itemId: Long!) { deleteItem(value: $itemId) }",
            | "variables": { "itemId": 1 }
            |}
            |""".stripMargin

        sendQueryAndCheckResult(query) { (statusCode, contentType, body) =>
          val jsObjectDeleteItemKeys = getKeysFromBody(body, "deleteItem")
          assert(statusCode)(equalTo(StatusCodes.OK)) &&
          assert(contentType)(equalTo(ContentTypes.`application/json`)) &&
          assert(jsObjectDeleteItemKeys)(isNone)
        }
      }
    ) @@ sequential).provideCustomLayerShared(env)

  private val systemLayer: ULayer[Has[ActorSystem]] = ZLayer.fromManaged {
    ZManaged.make(ZIO.effect(system).orDie)(s => ZIO.fromFuture(_ => s.terminate()).either)
  }

  private val env: ULayer[Has[GraphQLApi]] =
      ((InMemoryEventSubscriber.test ++ InMemoryItemRepository.test) >>> ApplicationService.live ++ systemLayer ++ Clock.live ++ Logging.ignore)  >>> GraphQLApi.live.orDie

  private def sendQueryAndCheckResult(query: String)(
    assertion: (StatusCode, ContentType, Json.Obj) => TestResult
  ): ZIO[Blocking with Has[GraphQLApi], Throwable, TestResult] =
    for {
      routes  <- GraphQLApi.routes
      request = Post("/api/graphql").withEntity(HttpEntity(ContentTypes.`application/json`, query))
      resultCheck <- effectBlocking {
                      request ~> routes ~> check {
                        val statusCode = status
                        val ct         = contentType
                        val body       = entityAs[Json.Obj]
                        assertion(statusCode, ct, body)
                      }
                    }
    } yield resultCheck

  private def getOperationTypeFromBody[T](body: Json.Obj)(implicit decoder: JsonDecoder[T]): Option[T] =
    for {
      jsonData     <- body.fields.toMap.get("data")
      jsObjectData <- jsonData.as[T].toOption
    } yield jsObjectData

  private def getKeysFromBody(body: Json.Obj, key: String): Option[collection.Set[String]] =
    for {
      jsonData           <- body.fields.toMap.get("data")
      jsObjectData       <- jsonData.as[Json.Obj].toOption
      jsonDeleteItem     <- jsObjectData.fields.toMap.get(key)
      jsObjectDeleteItem <- jsonDeleteItem.as[Json.Obj].toOption
      keys                = jsObjectDeleteItem.fields.map(_._1).toSet
    } yield keys
}