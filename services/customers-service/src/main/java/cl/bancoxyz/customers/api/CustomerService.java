package cl.bancoxyz.customers.api;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.bancoxyz.customers.domain.Customer;
import cl.bancoxyz.customers.domain.CustomerProfile;
import cl.bancoxyz.customers.domain.CustomerProfileRepository;
import cl.bancoxyz.customers.domain.CustomerRepository;
import cl.bancoxyz.customers.domain.CustomerStatus;
import cl.bancoxyz.platform.web.ApiException;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final CustomerProfileRepository profiles;

    public CustomerService(CustomerRepository customers, CustomerProfileRepository profiles) {
        this.customers = customers;
        this.profiles = profiles;
    }

    @Transactional
    public Customer create(CustomerRequest r) {
        String doc = r.documentNumber().toUpperCase();
        if (customers.existsByDocumentNumber(doc)) {
            throw ApiException.conflict("DUPLICATE_DOCUMENT", "Ya existe un cliente con el documento " + doc);
        }
        if (customers.existsByEmailIgnoreCase(r.email())) {
            throw ApiException.conflict("DUPLICATE_EMAIL", "Ya existe un cliente con el email " + r.email());
        }
        if (r.username() != null && customers.existsByUsername(r.username())) {
            throw ApiException.conflict("DUPLICATE_USERNAME", "El usuario " + r.username() + " ya está vinculado a otro cliente");
        }
        Customer c = customers.save(new Customer(doc, r.firstName(), r.lastName(), r.email().toLowerCase(), r.phone(), r.username()));
        profiles.save(new CustomerProfile(c.getId()));
        return c;
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        return customers.findById(id).orElseThrow(() -> ApiException.notFound("Cliente", id));
    }

    @Transactional
    public Customer update(UUID id, CustomerRequest r) {
        Customer c = get(id);
        if (!c.getDocumentNumber().equalsIgnoreCase(r.documentNumber())) {
            throw ApiException.unprocessable("DOCUMENT_IMMUTABLE", "El documento de un cliente no se puede modificar");
        }
        if (customers.existsByEmailIgnoreCaseAndIdNot(r.email(), id)) {
            throw ApiException.conflict("DUPLICATE_EMAIL", "Ya existe un cliente con el email " + r.email());
        }
        c.update(r.firstName(), r.lastName(), r.email().toLowerCase(), r.phone());
        if (r.username() != null && !r.username().equals(c.getUsername())) {
            if (c.getUsername() != null) {
                throw ApiException.unprocessable("USERNAME_IMMUTABLE", "El usuario vinculado no se puede cambiar");
            }
            if (customers.existsByUsernameAndIdNot(r.username(), id)) {
                throw ApiException.conflict("DUPLICATE_USERNAME", "El usuario " + r.username() + " ya está vinculado a otro cliente");
            }
            c.linkUsername(r.username());
        }
        return c;
    }

    @Transactional(readOnly = true)
    public Customer byUsername(String username) {
        return customers.findByUsername(username).orElseThrow(() -> ApiException.notFound("Cliente del usuario", username));
    }

    @Transactional(readOnly = true)
    public Page<Customer> list(CustomerStatus status, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("lastName", "firstName"));
        return status == null ? customers.findAll(pr) : customers.findByStatus(status, pr);
    }

    @Transactional(readOnly = true)
    public ProfileResponse profile(UUID id) {
        Customer c = get(id);
        return ProfileResponse.of(c, profiles.findById(id).orElse(null));
    }

    @Transactional
    public Customer deactivate(UUID id) {
        Customer c = get(id);
        c.deactivate();
        return c;
    }

    @Transactional
    public void delete(UUID id) {
        customers.delete(get(id));
    }
}
