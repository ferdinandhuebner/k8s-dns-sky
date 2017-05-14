package k8sdnssky
import java.io.{ByteArrayInputStream, InputStream, InputStreamReader, StringWriter}
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, PrivateKey, Security}
import java.util.Collections

import org.bouncycastle.asn1.{ASN1Integer, ASN1OctetString, ASN1Primitive, ASN1Sequence}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.io.pem.{PemObject, PemReader, PemWriter}

import scala.collection.JavaConverters._

object BouncyCastleUtil {

  def ensureBouncyCastleProviderIsRegistered(): Unit = {
    this.synchronized {
      if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(new BouncyCastleProvider())
      }
    }
  }

  ensureBouncyCastleProviderIsRegistered()

  private def pkcsOneToPkcsEight(pemObject: PemObject): String = {
    val keySpec: PKCS8EncodedKeySpec = new PKCS8EncodedKeySpec(pemObject.getContent)
    val factory: KeyFactory = KeyFactory.getInstance("RSA", "BC")
    val privateKey: PrivateKey = factory.generatePrivate(keySpec)

    val stringWriter = new StringWriter()
    val pemWriter = new PemWriter(stringWriter)
    try {
      val pemType = "RSA PRIVATE KEY"
      pemWriter.writeObject(new PemObject(pemType, privateKey.getEncoded))
      pemWriter.flush()
      stringWriter.toString
    } finally {
      stringWriter.close()
      pemWriter.close()
    }
  }

  /**
    * Loads the given private key as PKCS#8 for netty. The private key file must be in PEM form and
    * can be either PKCS#1 (usually the default when generated with openssl) or PKCS#8.
    */
  def loadPrivateKeyAsPkcsEightString(f: () => InputStream): () => InputStream = {
    val pemReader = new PemReader(new InputStreamReader(f.apply()))
    try {
      val pemObject = pemReader.readPemObject()
      val asn1Primitive = ASN1Primitive.fromByteArray(pemObject.getContent)

      val errorMessage = "Private key is not a PKCS#1 or PKCS#8 private key in PEM format."
      // see https://tls.mbed.org/kb/cryptography/asn1-key-structures-in-der-and-pem for a nice
      // description of ASN.1 key structures for PKCS#1 and PKCS#8 in PEM form
      asn1Primitive match {
        case asn1Sequence: ASN1Sequence =>
          val asn = Collections.list(asn1Sequence.getObjects).asScala
          // private key is in PKCS#1 and needs to be converted to PKCS#8 for netty
          if (asn.length == 9 || asn.length == 10) {
            if (asn.exists(x => !x.isInstanceOf[ASN1Integer])) {
              throw new IllegalArgumentException(errorMessage)
            } else {
              () => new ByteArrayInputStream(pkcsOneToPkcsEight(pemObject).getBytes)
            }
          } else if (asn.length == 3) {
            // private key is in PKCS#8 an can be passed to netty as-is
            //noinspection ZeroIndexToHead
            if (!asn(0).isInstanceOf[ASN1Integer] || !asn(1).isInstanceOf[ASN1Sequence]
                | !asn(2).isInstanceOf[ASN1OctetString]) {
              throw new IllegalArgumentException(errorMessage)
            } else {
              f
            }
          } else {
            throw new IllegalArgumentException(errorMessage)
          }
        case _ =>
          throw new IllegalArgumentException(errorMessage)
      }
    } finally {
      pemReader.close()
    }
  }
}
