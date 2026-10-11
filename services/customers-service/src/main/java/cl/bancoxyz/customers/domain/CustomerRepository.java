package cl.bancoxyz.customers.domain;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    boolean existsByDocumentNumber(String documentNumber);

    java.util.Optional<Customer> findByUsername(String username);

    boolean existsByUsernameAndIdNot(String username, UUID id);

    boolean existsByUsername(String username);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, UUID id);

    Page<Customer> findByStatus(CustomerStatus status, Pageable pageable);
}
