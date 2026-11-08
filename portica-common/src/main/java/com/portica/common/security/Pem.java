package com.portica.common.security;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PEM certificates and PKCS #8 private keys: parsing, fingerprints and key/certificate matching. */
public final class Pem {

    private static final Pattern BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END \\1-----");

    private Pem() {
    }

    /** Every {@code CERTIFICATE} block; throws {@link IllegalArgumentException} when none or one is invalid. */
    public static List<X509Certificate> certificates(String pem) {
        List<X509Certificate> out = new ArrayList<>();
        if (pem != null) {
            Matcher m = BLOCK.matcher(pem);
            while (m.find()) {
                if (!m.group(1).equals("CERTIFICATE")) {
                    continue;
                }
                try {
                    CertificateFactory factory = CertificateFactory.getInstance("X.509");
                    out.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(decode(m.group(2)))));
                } catch (CertificateException e) {
                    throw new IllegalArgumentException("Invalid certificate: " + e.getMessage(), e);
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("No PEM certificate found (-----BEGIN CERTIFICATE-----)");
        }
        return out;
    }

    /** A PKCS #8 private key ({@code BEGIN PRIVATE KEY}), RSA or EC. */
    public static PrivateKey privateKey(String pem) {
        Matcher m = BLOCK.matcher(pem == null ? "" : pem);
        while (m.find()) {
            if (m.group(1).equals("PRIVATE KEY")) {
                byte[] der = decode(m.group(2));
                for (String algorithm : new String[]{"RSA", "EC", "Ed25519"}) {
                    try {
                        return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
                    } catch (Exception ignored) {
                        // try the next algorithm
                    }
                }
                throw new IllegalArgumentException("Unsupported private key algorithm (use RSA, EC or Ed25519)");
            }
            if (m.group(1).endsWith("PRIVATE KEY")) {
                throw new IllegalArgumentException("Convert the key to PKCS #8 (BEGIN PRIVATE KEY): "
                        + "openssl pkcs8 -topk8 -nocrypt -in key.pem");
            }
        }
        throw new IllegalArgumentException("No PEM private key found (-----BEGIN PRIVATE KEY-----)");
    }

    /** SHA-256 of the certificate's DER encoding, lowercase hex: the usual certificate fingerprint. */
    public static String fingerprint(X509Certificate certificate) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The certificate as one PEM block (64-character lines). */
    public static String encode(X509Certificate certificate) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when {@code key} is the private half of the certificate's public key (sign and verify). */
    public static boolean matches(PrivateKey key, X509Certificate certificate) {
        String algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> key.getAlgorithm();
        };
        try {
            byte[] data = "portica-key-check".getBytes(StandardCharsets.UTF_8);
            java.security.Signature signer = java.security.Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(data);
            byte[] signature = signer.sign();
            java.security.Signature verifier = java.security.Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] decode(String base64) {
        return Base64.getMimeDecoder().decode(base64.replaceAll("\\s", ""));
    }
}
