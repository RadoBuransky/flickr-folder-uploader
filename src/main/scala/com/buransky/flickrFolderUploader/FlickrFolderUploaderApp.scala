package com.buransky.flickrFolderUploader

import java.io._
import javax.swing.filechooser.FileNameExtensionFilter

import com.flickr4java.flickr.auth.Permission
import com.flickr4java.flickr.photos.SearchParameters
import com.flickr4java.flickr.photosets.PhotosetsInterface
import com.flickr4java.flickr.uploader.{UploadMetaData, Uploader}
import com.flickr4java.flickr.util.FileAuthStore
import com.flickr4java.flickr.{Flickr, REST, RequestContext}
import org.scribe.model.Verifier

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.StdIn

object FlickrFolderUploaderApp {
  val fileNameToPhotoId = mutable.HashMap[String, String]()
  val fileNameToPhotoIdPath = "fileNameToPhotoId.txt"
  var bufferedWriter: BufferedWriter = _

  def main(args: Array[String]): Unit = {
    val userId = args(0)
    val apiKey = args(1)
    val apiSecret = args(2)
    val rootDir = new File(args(3))
    println(s"User ID: [$userId]")
    println(s"Flickr API key: [$apiKey]")
    println(s"Flickr API secret: [$apiSecret]")
    println(s"Root dir: $rootDir")

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

    val photosets = flickr.getPhotosetsInterface
    val uploader = flickr.getUploader
    val all = photosets.getList(userId).getPhotosets.asScala

    loadFileNameToPhotoIds()
    bufferedWriter = new BufferedWriter(new FileWriter(new File(fileNameToPhotoIdPath), true))
    try {
      // Upload all albums
      rootDir.listFiles().sortBy(_.getName).foreach { file =>
        if (file.isDirectory && !all.exists(_.getTitle == file.getName)) {
          uploadAlbum(file, uploader, photosets)
        }
      }
    }
    finally {
      bufferedWriter.close()
    }

    // Print all albums:
//    println(photosets.getList(userId).getPhotosets.asScala.map(_.getTitle).mkString(","))

    // Create new album
//    val newAlbum = photosets.create("title", "description", "27549264665")
//    println(newAlbum.getUrl)
  }

  private def loadFileNameToPhotoIds(): Unit = {
    val file = new File(fileNameToPhotoIdPath)
    if (file.exists()) {
      val br = new BufferedReader(new FileReader(file))
      try {
        var counter = 0
        var k = ""
        do {
          k = br.readLine()
          if (k != null) {
            val v = br.readLine()
            fileNameToPhotoId.update(k, v)
            counter += 1
          }
        } while (k != null)

        println(s"$counter photo IDs read from file.")
      }
      finally {
        br.close()
      }
    }
  }

  private def uploadAlbum(directory: File,
                          uploader: Uploader,
                          photosets: PhotosetsInterface): Unit = {
    println(s"Uploading album ${directory.getName} ...")

    val fileFilter = new FilenameFilter() {
      override def accept(dir: File, name: String): Boolean = {
        val lc = name.toLowerCase
        lc.endsWith("jpg") || lc.endsWith("jpeg")
      }
    }

    // Upload all JPEGs
    // TODO: sort by time created
    val photoIds = directory.listFiles(fileFilter).toList.flatMap { photoFile =>
      fileNameToPhotoId.get(photoFile.getAbsolutePath) match {
        case Some(photoId) =>
          println(s"Photo already uploaded. Skipping. [${photoFile.getAbsolutePath}]")
          Some(photoId)

        case None =>
          val metadata = new UploadMetaData()
          metadata.setFilename(photoFile.getName)
          metadata.setFilemimetype("image/jpeg")
          metadata.setTitle(photoFile.getName)
          metadata.setPublicFlag(false)
          metadata.setFriendFlag(false)
          metadata.setFamilyFlag(true)
          metadata.setHidden(false)
          metadata.setSafetyLevel("SAFETYLEVEL_SAFE")
          metadata.setContentType("CONTENTTYPE_PHOTO")

          try {
            // Upload synchronously
            val photoId = uploader.upload(photoFile, metadata)
            println(s"Photo uploaded. [${photoFile.getName}, $photoId]")

            bufferedWriter.write(photoFile.getAbsolutePath)
            bufferedWriter.write("\n")
            bufferedWriter.write(photoId)
            bufferedWriter.write("\n")
            bufferedWriter.flush()

            fileNameToPhotoId.update(photoFile.getAbsolutePath, photoId)

            Some(photoId)
          }
          catch {
            case ex: Exception =>
              println(ex)
              None
          }
      }
    }

    if (photoIds.nonEmpty) {
      // Create album
      val newAlbum = photosets.create(directory.getName, "", photoIds.head)
      print(s"New album created ${newAlbum.getUrl}. Adding photos ... ")
      photoIds.tail.foreach { photoId =>
        try {
          photosets.addPhoto(newAlbum.getId, photoId)
        }
        catch {
          case ex: Exception => throw new AppException(s"Cannot add photo to album! [${newAlbum.getId}, $photoId]")
        }
      }
      println("done.")
    }
    else {
      println(s"No photos uploaded. Album not created.")
    }
  }
}

class AppException(message: String, cause: Throwable = null) extends RuntimeException(message, cause)