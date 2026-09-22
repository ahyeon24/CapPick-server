package com.cappick.domain.installation;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallationRepository extends JpaRepository<Installation, String> {

    Optional<Installation> findByCredentialHash(String credentialHash);

    Optional<Installation> findByKeyId(String keyId);

    boolean existsByKeyId(String keyId);
}
