package com.cappick.external.attestation.apple;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

import org.junit.jupiter.api.RepeatedTest;

// rawEcPoint/decodeEcPublicKey는 BigInteger <-> 고정 32바이트 변환이 핵심이라 leading-zero 케이스가
// 반복돼야 잡힌다 — @RepeatedTest로 매번 새 키를 생성해 여러 번 돌린다.
class EcPointEncodingTest {

    @RepeatedTest(50)
    void rawPointRoundTripsThroughDecode() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        ECPublicKey original = (ECPublicKey) keyPair.getPublic();

        byte[] point = AppleAppAttestVerifier.rawEcPoint(original);
        assertEquals(65, point.length, "P-256 uncompressed point is 1 + 32 + 32 bytes");
        assertEquals(0x04, point[0] & 0xFF);

        ECPublicKey decoded = (ECPublicKey) AppleAppAttestVerifier.decodeEcPublicKey(point);

        assertEquals(original.getW().getAffineX(), decoded.getW().getAffineX());
        assertEquals(original.getW().getAffineY(), decoded.getW().getAffineY());
    }
}
