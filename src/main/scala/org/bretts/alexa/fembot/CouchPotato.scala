package org.bretts.alexa.fembot

import java.net.URLEncoder

import akka.http.scaladsl._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.amazon.speech.speechlet.{IntentRequest, Session, SpeechletResponse}
import com.typesafe.scalalogging.StrictLogging
import org.bretts.alexa.util._
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

case class Movie(title: String, status: String) {
  def toSpokenString: String = s"$title is $spokenStatus"

  private def spokenStatus = status match {
    case "active" => "queued"
    case "done" => "downloaded"
    case s => s
  }
}
case class FindResponse(movies: Option[Seq[Movie]])

case class SearchMovie(imdb: Option[String], titles: Seq[String], year: Option[Int])
case class SearchResponse(movies: Option[Seq[SearchMovie]])

case class SimpleResponse(success: Boolean)

object CouchPotatoJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val movieFormat: RootJsonFormat[Movie] = jsonFormat2(Movie)
  implicit val findResponseFormat: RootJsonFormat[FindResponse] = jsonFormat1(FindResponse)
  implicit val searchMovieFormat: RootJsonFormat[SearchMovie] = jsonFormat3(SearchMovie)
  implicit val searchResponseFormat: RootJsonFormat[SearchResponse] = jsonFormat1(SearchResponse)
  implicit val simpleResponseFormat: RootJsonFormat[SimpleResponse] = jsonFormat1(SimpleResponse)
}

class CouchPotato(url: String, apiKey: String) extends StrictLogging {
  import CouchPotatoJsonSupport._

  def listMovies(search: Option[String]): Future[Seq[Movie]] = {
    val requestUrl = s"$url/api/$apiKey/media.list/" +
      search.map(s => s"?search=${URLEncoder.encode(s, "UTF-8")}").getOrElse("")
    logger.info(s"listMovies URL: $requestUrl")
    val response: Future[FindResponse] = Http().singleRequest(HttpRequest(
      uri = requestUrl
    )).flatMap(Unmarshal(_).to[FindResponse])

    response.map(_.movies.getOrElse(Seq()))
  }

  def searchProviders(search: String): Future[Seq[SearchMovie]] = {
    val requestUrl = s"$url/api/$apiKey/search/?q=${URLEncoder.encode(search, "UTF-8")}"
    logger.info(s"searchProviders URL: $requestUrl")
    val response: Future[SearchResponse] = Http().singleRequest(HttpRequest(
      uri = requestUrl
    )).flatMap(Unmarshal(_).to[SearchResponse])

    response.map(_.movies.getOrElse(Seq()))
  }

  def addMovie(imdb: String): Future[Boolean] = {
    val requestUrl = s"$url/api/$apiKey/movie.add/?identifier=$imdb}"
    logger.info(s"addMovie URL: $requestUrl")
    val response: Future[SimpleResponse] = Http().singleRequest(HttpRequest(
      uri = requestUrl
    )).flatMap(Unmarshal(_).to[SimpleResponse])

    response.map(_.success)
  }
}

object CouchPotatoSpeechModel {
  val Intent = "CouchPotato(.*)".r
  val NumRegex = "[0-9]".r.unanchored
  val DrRegex = "Dr.".r.unanchored
}

class CouchPotatoSpeechModel(url: String, apiKey: String) extends StrictLogging {
  import CouchPotatoJsonSupport._

  val cp = new CouchPotato(url, apiKey)

  def handle(request: IntentRequest, session: Session): SpeechletResponse = {
    session.continuation match {
      case None =>
        request.intent match {
          case "CouchPotatoFind" =>
            val searchText = request.slotOption("movie")
            val alts = searchText match {
              case Some(s) => alternatives(s).map(Some(_))
              case None => Seq(None)
            }
            val results = Future.traverse(alts)(cp.listMovies).map(_.flatten)
            Await.result(results, timeout) match {
              case Seq() =>
                searchText match {
                  case Some(s) => reply(s"$s is not currently added")
                  case None => reply("No movies are currently added")
                }
              case Seq(m) =>
                reply(m.toSpokenString)
              case ms if ms.size <= 5 =>
                reply("I found the following movies: " + ms.map(_.toSpokenString).mkString(", "))
              case ms =>
                reply(s"I found ${ms.size} movies. The first five found were: " +
                  ms.take(5).map(_.toSpokenString).mkString(", "))
            }
          case "CouchPotatoAdd" =>
            val title = request.slotOption("movie")
            title.map { t =>
              Await.result(cp.searchProviders(t), timeout) match {
                case head +: tail =>
                  requestAddResponse(head, tail, session)
                case Seq() =>
                  reply(s"No movies found named $t")
              }
            }.getOrElse(reply("Please specify a movie name"))
        }
      case Some("CouchPotatoAdd") =>
        val movie = session.attribute[SearchMovie]("movie")
        request.intent match {
          case "AMAZON.YesIntent" =>
            if (movie.imdb.exists(imdb => Await.result(cp.addMovie(imdb), timeout)))
              SpeechletResponse.newTellResponse(output(s"${movie.titles.head} successfully added"))
            else
              SpeechletResponse.newTellResponse(output(s"${movie.titles.head} could not be added"))
          case "AMAZON.NoIntent" =>
            session.attribute[Seq[SearchMovie]]("remaining") match {
              case head +: tail =>
                requestAddResponse(head, tail, session)
              case Seq() =>
                val name = session.attribute[String]("name")
                SpeechletResponse.newTellResponse(output(s"No more movies found named $name"))
            }
          case "AMAZON.StopIntent" =>
            SpeechletResponse.newTellResponse(output("OK, stopped"))
        }
      case intent =>
        reply(s"Sorry, I didn't understand the intent $intent")
    }
  }

  private def requestAddResponse(movie: SearchMovie, remaining: Seq[SearchMovie], session: Session): SpeechletResponse = {
    session.continuation = "CouchPotatoAdd"
    session.attribute_=("movie", movie)
    session.attribute_=("remaining", remaining)
    val yearText = movie.year.map(y => s" from $y")
    val responseText = s"Did you mean ${movie.titles.head}${yearText}? You can answer 'Yes', 'No', or 'Stop'."
    ask(responseText)
  }

  private def alternatives(name: String): Seq[String] = {
    def alphanum(s: String) = s match {
      case CouchPotatoSpeechModel.NumRegex() =>
        val replaced = s.replace("1", "one").replace("2", "two").replace("3", "three").replace("4", "four")
          .replace("5", "five").replace("6", "six").replace("7", "seven").replace("8", "eight")
          .replace("9", "nine").replace("0", "zero")
        Seq(s, replaced)
      case _ =>
        Seq(s)
    }
    def honorific(s: String) = s match {
      case CouchPotatoSpeechModel.DrRegex() =>
        val replaced = s.replace("Dr.", "Doctor")
        Seq(s, replaced)
    }
    for {
      a <- alphanum(name)
      b <- honorific(a)
    } yield b
  }

}

object CouchPotatoTest extends App {
  try {
    val r = new CouchPotato(sys.env("CP_URL"), sys.env("CP_API_KEY")).searchProviders("rogue one")
    println(Await.result(r, 20 seconds))
  } finally {
    system.terminate()
  }
}
