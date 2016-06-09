package com.buransky.flickrFolderUploader

import java.io.File

import com.flickr4java.flickr.auth.{Auth, Permission}
import com.flickr4java.flickr.util.FileAuthStore
import com.flickr4java.flickr.{Flickr, REST, RequestContext}
import org.scribe.model.Verifier

import scala.collection.JavaConverters._
import scala.io.StdIn

object FlickrFolderUploaderApp {
  def main(args: Array[String]): Unit = {
    val userId = args(0)
    val apiKey = args(1)
    val apiSecret = args(2)
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

    val galleries = flickr.getGalleriesInterface
    val all = galleries.getList(userId, 0, 0).asScala.map(_.getTitle).mkString(",")
    println(all)

//    val photoset = photosets.create("title", "description", "27549265605")
//    println(s"Photoset created. [${photoset.getId}]")
  }

  private def authorize(): Unit = {

  }
}
