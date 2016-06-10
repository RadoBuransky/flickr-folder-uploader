package com.buransky.flickrFolderUploader

import java.io._
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.util.{Date, TimeZone}

import com.flickr4java.flickr.auth.Permission
import com.flickr4java.flickr.util.FileAuthStore
import com.flickr4java.flickr.{Flickr, REST, RequestContext}
import org.scribe.model.Verifier

import scala.collection.JavaConverters._
import scala.io.StdIn

object FlickrAlbumSorterApp {
  val tz = TimeZone.getTimeZone("UTC")
  val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmZ")
  df.setTimeZone(tz)

  val photosetTitlePattern = """(\d\d\d\d).(\d\d).(\d\d).*""".r

  def main(args: Array[String]): Unit = {
    val userId = args(0)
    val apiKey = args(1)
    val apiSecret = args(2)
    println(s"User ID: [$userId]")
    println(s"Flickr API key: [$apiKey]")
    println(s"Flickr API secret: [$apiSecret]")

    val flickr = new Flickr(apiKey, apiSecret, new REST())
    val authInterface = flickr.getAuthInterface
    val accessToken = authInterface.getRequestToken

    val authStore = new FileAuthStore(new File("authStore"))
    val auth = authStore.retrieveAll().headOption match {
      case Some(a) => a
      case None =>
        val authUrl = authInterface.getAuthorizationUrl(accessToken, Permission.DELETE)

        println(s"Authorization URL: $authUrl")
        val tokenKey = StdIn.readLine()

        val requestToken = authInterface.getAccessToken(accessToken, new Verifier(tokenKey))
        val a = authInterface.checkToken(requestToken)
        authStore.store(a)
        a
    }
    RequestContext.getRequestContext.setAuth(auth)

    val photos = flickr.getPhotosInterface
    val photosets = flickr.getPhotosetsInterface

    // Process all photosets
    photosets.getList(userId).getPhotosets.asScala.map(_.getId).dropWhile(_ != "72157666911045484").foreach { photosetId =>
      try {
        val photoset = photosets.getInfo(photosetId)
        val photosetParsedDate = photoset.getTitle match {
          case photosetTitlePattern(year, month, day) => Some(new Date(year.toInt - 1900, month.toInt - 1, day.toInt))
          case other =>
            println(s"Cannot parse album title. [$other]")
            None
        }

        val sortOrder = photosets.getPhotos(photosetId, 0, 0).asScala.map { photo =>
          val index = Option(photos.getInfo(photo.getId, null).getDateTaken) match {
            case Some(dateTaken) if dateTaken == photo.getDateTaken =>
              df.format(dateTaken)

            case Some(dateTaken) =>
              val dayDiff = TimeUnit.MILLISECONDS.toDays(Math.abs(dateTaken.getTime - photosetParsedDate.get.getTime))
              val fixedDate = if (photosetParsedDate.nonEmpty && dayDiff > 7) {
                println(s"Fixing date taken using album title because it seems better. [${photosetParsedDate.get}, ${photo.getDateTaken}]")
                photosetParsedDate.get
              }
              else {
                println(s"Fixing date taken. [$dateTaken, ${photo.getDateTaken}]")
                dateTaken
              }

              try {
                photos.setDates(photo.getId, null, fixedDate, null)
              }
              catch {
                case ex: Exception => println(ex)
              }
              df.format(dateTaken)

            case None =>
              if (photosetParsedDate.nonEmpty) {
                println(s"Fixing date taken using album title. [${photoset.getTitle}, ${photo.getTitle}, ${photosetParsedDate.get}]")
                try {
                  photos.setDates(photo.getId, null, photosetParsedDate.get, null)
                }
                catch {
                  case ex: Exception => println(ex)
                }
              }
              photosetParsedDate.map(df.format).getOrElse("")
          }

          (index + photo.getTitle, photo.getId)
        }

        val orderedIds = sortOrder.sortBy(_._1).map(_._2).toSeq
        photosets.setPrimaryPhoto(photosetId, orderedIds(orderedIds.size / 2))
        photosets.reorderPhotos(photosetId, orderedIds.mkString(","))
        println(s"Photoset reordered ${photoset.getUrl}")
      }
      catch {
        case ex: Exception =>
          println(ex.toString)
      }
    }
  }
}