package cl.bancoxyz.customers.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, UUID> {
}
