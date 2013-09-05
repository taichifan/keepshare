package com.hanhuy.android.keepshare

import RichLogger._

import collection.JavaConversions._

import java.security.{SecureRandom, MessageDigest}
import com.google.api.client.googleapis.extensions.android.gms.auth._
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.{Drive, DriveScopes}
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.http.{ByteArrayContent, GenericUrl}
import java.nio.ByteBuffer
import com.google.api.services.drive.model.{File, ParentReference}
import android.app.Activity
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import android.content.Context

object KeyManager {
  val VERIFIER = "KeePass Share Verifier"

  val STATE_SAVE = "save"
  val STATE_LOAD = "load"

  val EXTRA_STATE = "com.hanhuy.android.keepshare.extra.STATE"

  lazy val sha1 = MessageDigest.getInstance("SHA1")
  def sha1(b: Array[Byte]): String = hex(sha1.digest(b))

  private var _cloudKey: Array[Byte] = _

  def cloudKey = _cloudKey

  def clear() {
    _cloudKey = null
  }

  // prefix with 01 and strip it off, otherwise
  // prefixed 00's will be dropped from output
  // this inserts a sign byte at the beginning
  // if the first byte is > 0x7f strip it off
  def bytes(hex: String) =
    BigInt("01" + hex, 16).toByteArray.takeRight(hex.size / 2)
  //def bytes(hex: String): Array[Byte] =
  //  hex.grouped(2).map (Integer.parseInt(_, 16).asInstanceOf[Byte]).toArray

  def hex(b: Array[Byte]) =
    b.map { byte => "%02X" format (byte & 0xff) }.mkString

  val random = new SecureRandom
  val ALG = "AES"
  val CIPHER_ALG = ALG + "/CBC/PKCS5Padding"

  /** @return a hex string iv:encrypted
    */
  def encrypt(k: SecretKeySpec, data: Array[Byte]): String = {
    val cipher = Cipher.getInstance(CIPHER_ALG)
    val iv = Array.ofDim[Byte](16)
    random.nextBytes(iv)
    val ivspec = new IvParameterSpec(iv)
    cipher.init(Cipher.ENCRYPT_MODE, k, ivspec, random)
    hex(iv) + ":" + hex(cipher.doFinal(data))
  }

  def encrypt(k: SecretKeySpec, data: String): String =
    encrypt(k, data.getBytes("utf-8"))

  /** @param data iv:encrypted in hex
    * @return a byte array
    */
  def decrypt(k: SecretKeySpec, data: String): Array[Byte] = {
    val cipher = Cipher.getInstance(CIPHER_ALG)
    data.split(":") match {
      case Array(ivs, encs) =>
        val iv = bytes(ivs)
        val encrypted = bytes(encs)
        val ivspec = new IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, k, ivspec)
        cipher.doFinal(encrypted)
    }
  }

  def decryptToString(k: SecretKeySpec, data: String): String =
    new String(decrypt(k, data), "utf-8")
}
class KeyManager(c: Context, settings: Settings) {
  import RequestCodes._

  import KeyManager._
  implicit val TAG = LogcatTag("KeyManager")

  private var nameSelected = false
  def accountName = credential.getSelectedAccountName
  def accountName_=(a: String) = {
    nameSelected = true
    credential.setSelectedAccountName(a)
  }

  def newChooseAccountIntent = credential.newChooseAccountIntent()

  private lazy val credential = GoogleAccountCredential.usingOAuth2(c,
    Seq(DriveScopes.DRIVE_APPDATA))

  val KEY_FILE = "keepass-share.key"

  lazy val drive = new Drive.Builder(AndroidHttp.newCompatibleTransport,
    new GsonFactory, credential).build

  def loadKey(): Array[Byte] = {
    if (!nameSelected)
      throw new IllegalStateException("account name has not been set")

    Option(KeyManager.cloudKey) getOrElse {
      val req = drive.files.list
      req.setQ("'appdata' in parents")
      try {
        val files = req.execute()
        files.getItems find (_.getTitle == KEY_FILE) map {
          file =>
            val resp = drive.getRequestFactory.buildGetRequest(
              new GenericUrl(file.getDownloadUrl)).execute
            val buf = Array.ofDim[Byte](32)
            val in = resp.getContent
            val b = ByteBuffer.allocate(32)
            Stream.continually(in.read(buf)).takeWhile(_ != -1) foreach { r =>
              b.put(buf, 0, r)
            }
            b.flip()
            if (b.remaining != 32) {
              e("wrong buffer size: " + b.remaining)
              return null
            }
            b.get(buf)
            val hash = settings.get(Settings.CLOUD_KEY_HASH)
            if (hash != null && sha1(buf) != hash) {
              e("cloud key has changed")
              return null
            }
            _cloudKey = buf
            v("Loaded cloud key")
        } getOrElse createKey()
      } catch {
        case e: UserRecoverableAuthIOException => requestAuthz(e, STATE_LOAD)
      }
      KeyManager.cloudKey
    }
  }

  def createKey(): Array[Byte] = {
    val keybuf = Array.ofDim[Byte](32)
    random.nextBytes(keybuf)
    settings.set(Settings.CLOUD_KEY_HASH, sha1(keybuf))

    val content = new ByteArrayContent("application/octet-stream", keybuf)
    val f = new File
    f.setTitle(KEY_FILE)
    f.setParents(Seq(new ParentReference().setId("appdata")))
    try {
      val r = drive.files.insert(f, content).execute()
      loadKey()
    } catch {
      case e: UserRecoverableAuthIOException =>
        requestAuthz(e, STATE_SAVE)
        null
    }
  }

  lazy val localKey: SecretKeySpec = {
    if (cloudKey == null)
      throw new IllegalStateException("Cloud Key must be loaded first")

    val k = settings.get(Settings.LOCAL_KEY)
    val ckey = new SecretKeySpec(cloudKey, ALG)

    if (k == null) {
      val keybuf = Array.ofDim[Byte](32)
      random.nextBytes(keybuf)

      settings.set(Settings.LOCAL_KEY, encrypt(ckey, keybuf))
      new SecretKeySpec(keybuf, ALG)
    } else {
      new SecretKeySpec(decrypt(ckey, k), ALG)
    }
  }

  private def requestAuthz(e: UserRecoverableAuthIOException, state: String) {
    val i = e.getIntent
    i.putExtra(EXTRA_STATE, state)
    c match {
      case a: Activity =>
        a.startActivityForResult(i, REQUEST_AUTHORIZATION)
      case _ =>
    }
  }
}
