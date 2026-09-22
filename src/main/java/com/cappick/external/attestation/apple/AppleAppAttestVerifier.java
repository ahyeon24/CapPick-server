package com.cappick.external.attestation.apple;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;

import com.cappick.common.exception.AttestationVerificationException;
import com.cappick.external.attestation.AssertionOutcome;
import com.cappick.external.attestation.AssertionRequest;
import com.cappick.external.attestation.AttestationOutcome;
import com.cappick.external.attestation.AttestationRequest;
import com.cappick.external.attestation.AttestationVerifier;
import com.upokecenter.cbor.CBORObject;

/**
 * Apple App Attest 실제 검증 구현. Apple 공식 문서("Validating apps that connect to your server")의
 * 알고리즘을 따랐다.
 *
 * ⚠️ 실제 Apple 기기가 생성한 attestation/assertion 페이로드로 동작 확인은 아직 안 했다 — Apple
 * Developer Program 미가입 상태라 iOS 쪽에서 진짜 App Attest 호출 자체를 아직 못 만들었다
 * ({@code CapPick-ios/CLAUDE.md} 참고). 루트 인증서는 Apple 공식 URL(apple.com/certificateauthority)에서
 * 직접 받아 검증했지만, CBOR 파싱·인증서 체인 검증·공개키 해시 비교·서명 검증 각 단계는 합성
 * 데이터로만 단위 테스트했다. 실기기 페이로드를 처음 받으면 이 클래스부터 재검증할 것.
 */
public class AppleAppAttestVerifier implements AttestationVerifier {

    private static final String ROOT_CERT_RESOURCE = "apple/app-attest-root-ca.pem";
    private static final byte[] DEVELOPMENT_AAGUID = "appattestdevelop".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] PRODUCTION_AAGUID = {
        'a', 'p', 'p', 'a', 't', 't', 'e', 's', 't', 0, 0, 0, 0, 0, 0, 0
    };

    private final AppleAppAttestProperties properties;
    private final X509Certificate rootCertificate;

    public AppleAppAttestVerifier(AppleAppAttestProperties properties) {
        this.properties = properties;
        this.rootCertificate = loadRootCertificate();
    }

    @Override
    public AttestationOutcome verifyAttestation(AttestationRequest request) {
        try {
            CBORObject attestationObject = CBORObject.DecodeFromBytes(request.attestationObject());
            String fmt = attestationObject.get("fmt").AsString();
            if (!"apple-appattest".equals(fmt)) {
                throw new AttestationVerificationException("unexpected attestation fmt: " + fmt);
            }

            CBORObject attStmt = attestationObject.get("attStmt");
            CBORObject x5c = attStmt.get("x5c");
            X509Certificate credCert = decodeCertificate(x5c.get(0).GetByteString());
            X509Certificate intermediateCert = decodeCertificate(x5c.get(1).GetByteString());
            byte[] authData = attestationObject.get("authData").GetByteString();

            validateChain(credCert, intermediateCert);

            byte[] clientDataHash = sha256(request.challenge());
            byte[] expectedNonce = sha256(concat(authData, clientDataHash));
            byte[] actualNonce = AppleNonceExtension.extractNonce(
                requireExtension(credCert, AppleNonceExtension.OID));
            if (!MessageDigest.isEqual(expectedNonce, actualNonce)) {
                throw new AttestationVerificationException("attestation nonce mismatch");
            }

            byte[] keyIdBytes = decodeBase64(request.keyId());
            byte[] publicKeyPoint = rawEcPoint(credCert.getPublicKey());
            if (!MessageDigest.isEqual(sha256(publicKeyPoint), keyIdBytes)) {
                throw new AttestationVerificationException("public key does not match keyId");
            }

            AppleAuthenticatorData authenticatorData = AppleAuthenticatorData.parseForAttestation(authData);
            if (!authenticatorData.rpIdHashMatches(sha256(properties.appId().getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                throw new AttestationVerificationException("rpIdHash does not match configured app id");
            }
            if (authenticatorData.counter() != 0) {
                throw new AttestationVerificationException("attestation counter must be 0, was " + authenticatorData.counter());
            }
            byte[] expectedAaguid = properties.isDevelopment() ? DEVELOPMENT_AAGUID : PRODUCTION_AAGUID;
            if (!Arrays.equals(authenticatorData.aaguid(), expectedAaguid)) {
                throw new AttestationVerificationException("unexpected AAGUID for configured environment");
            }
            if (!Arrays.equals(authenticatorData.credentialId(), keyIdBytes)) {
                throw new AttestationVerificationException("authData credentialId does not match keyId");
            }

            return new AttestationOutcome(request.keyId(), Base64.getEncoder().encodeToString(publicKeyPoint));
        } catch (AttestationVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new AttestationVerificationException("attestation verification failed: " + e.getMessage(), e);
        }
    }

    @Override
    public AssertionOutcome verifyAssertion(AssertionRequest request) {
        try {
            CBORObject assertion = CBORObject.DecodeFromBytes(request.assertionObject());
            byte[] signature = assertion.get("signature").GetByteString();
            byte[] authData = assertion.get("authenticatorData").GetByteString();

            AppleAuthenticatorData authenticatorData = AppleAuthenticatorData.parseForAssertion(authData);
            if (!authenticatorData.rpIdHashMatches(sha256(properties.appId().getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                throw new AttestationVerificationException("rpIdHash does not match configured app id");
            }
            if (authenticatorData.counter() <= request.previousSignCount()) {
                throw new AttestationVerificationException(
                    "assertion counter did not increase: " + authenticatorData.counter()
                        + " <= " + request.previousSignCount());
            }

            byte[] clientDataHash = sha256(request.challenge());
            byte[] nonce = sha256(concat(authData, clientDataHash));

            PublicKey publicKey = decodeEcPublicKey(decodeBase64(request.publicKeyBase64()));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(nonce);
            if (!verifier.verify(signature)) {
                throw new AttestationVerificationException("assertion signature invalid");
            }

            return new AssertionOutcome(authenticatorData.counter());
        } catch (AttestationVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new AttestationVerificationException("assertion verification failed: " + e.getMessage(), e);
        }
    }

    private void validateChain(X509Certificate credCert, X509Certificate intermediateCert) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            CertPath path = factory.generateCertPath(List.of(credCert, intermediateCert));
            PKIXParameters params = new PKIXParameters(Set.of(new TrustAnchor(rootCertificate, null)));
            params.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(path, params);
        } catch (GeneralSecurityException e) {
            throw new AttestationVerificationException("certificate chain validation failed: " + e.getMessage(), e);
        }
    }

    /** SubjectPublicKeyInfo의 BIT STRING 내용 = EC 비압축 포인트(0x04 || X || Y)를 그대로 꺼낸다. */
    static byte[] rawEcPoint(PublicKey publicKey) {
        if (!(publicKey instanceof ECPublicKey ecPublicKey)) {
            throw new AttestationVerificationException("credential certificate key is not EC");
        }
        int fieldSize = (ecPublicKey.getParams().getCurve().getField().getFieldSize() + 7) / 8;
        byte[] x = toFixedLength(ecPublicKey.getW().getAffineX().toByteArray(), fieldSize);
        byte[] y = toFixedLength(ecPublicKey.getW().getAffineY().toByteArray(), fieldSize);
        byte[] point = new byte[1 + fieldSize * 2];
        point[0] = 0x04;
        System.arraycopy(x, 0, point, 1, fieldSize);
        System.arraycopy(y, 0, point, 1 + fieldSize, fieldSize);
        return point;
    }

    private static byte[] toFixedLength(byte[] value, int length) {
        if (value.length == length) {
            return value;
        }
        byte[] out = new byte[length];
        int srcStart = Math.max(0, value.length - length);
        int destStart = Math.max(0, length - value.length);
        System.arraycopy(value, srcStart, out, destStart, value.length - srcStart);
        return out;
    }

    static PublicKey decodeEcPublicKey(byte[] rawPoint) throws GeneralSecurityException {
        // 저장된 형식이 원시 EC 포인트(04||X||Y)라 SubjectPublicKeyInfo로 다시 감싸야 KeyFactory가 읽는다.
        // P-256(secp256r1) 고정 — App Attest 키는 이 곡선만 쓴다(Apple 문서 기준).
        java.security.spec.ECParameterSpec p256Params = loadP256Params();
        int fieldSize = (p256Params.getCurve().getField().getFieldSize() + 7) / 8;
        if (rawPoint.length != 1 + fieldSize * 2 || rawPoint[0] != 0x04) {
            throw new AttestationVerificationException("stored public key is not an uncompressed EC point");
        }
        byte[] xBytes = Arrays.copyOfRange(rawPoint, 1, 1 + fieldSize);
        byte[] yBytes = Arrays.copyOfRange(rawPoint, 1 + fieldSize, 1 + fieldSize * 2);
        java.security.spec.ECPoint point = new java.security.spec.ECPoint(
            new java.math.BigInteger(1, xBytes), new java.math.BigInteger(1, yBytes));
        java.security.spec.ECPublicKeySpec spec = new java.security.spec.ECPublicKeySpec(point, p256Params);
        return KeyFactory.getInstance("EC").generatePublic(spec);
    }

    private static java.security.spec.ECParameterSpec loadP256Params() throws GeneralSecurityException {
        java.security.AlgorithmParameters params = java.security.AlgorithmParameters.getInstance("EC");
        params.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return params.getParameterSpec(java.security.spec.ECParameterSpec.class);
    }

    private static byte[] requireExtension(X509Certificate cert, String oid) {
        byte[] value = cert.getExtensionValue(oid);
        if (value == null) {
            throw new AttestationVerificationException("credential certificate is missing extension " + oid);
        }
        return value;
    }

    private static X509Certificate decodeCertificate(byte[] der) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new java.io.ByteArrayInputStream(der));
        } catch (CertificateException e) {
            throw new AttestationVerificationException("malformed certificate", e);
        }
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new AttestationVerificationException("malformed base64: " + value, e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static X509Certificate loadRootCertificate() {
        try (InputStream in = new ClassPathResource(ROOT_CERT_RESOURCE).getInputStream()) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load " + ROOT_CERT_RESOURCE, e);
        } catch (CertificateException e) {
            throw new IllegalStateException("failed to parse " + ROOT_CERT_RESOURCE, e);
        }
    }
}
