package com.banco.clientes.config;

import com.banco.clientes.model.Cliente;
import com.banco.clientes.model.EstadoCliente;
import com.banco.clientes.model.PerfilCliente;
import com.banco.clientes.repository.ClienteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private final ClienteRepository clienteRepository;

    public DataInitializer(ClienteRepository clienteRepository) {
        this.clienteRepository = clienteRepository;
    }

    @Override
    public void run(String... args) {
        if (clienteRepository.count() == 0) {
            try {
                clienteRepository.save(crearCliente("11111111-1", "Ana", "Perez", "ana.perez@correo.cl",
                        "+56911111111", "Av. Providencia 1234, Santiago", PerfilCliente.BASICO));
                clienteRepository.save(crearCliente("22222222-2", "Luis", "Soto", "luis.soto@correo.cl",
                        "+56922222222", "Calle Valparaiso 567, Vina del Mar", PerfilCliente.PREFERENTE));
                clienteRepository.save(crearCliente("33333333-3", "Maria", "Rojas", "maria.rojas@correo.cl",
                        "+56933333333", "Av. Alemania 890, Temuco", PerfilCliente.PREMIUM));
            } catch (DataIntegrityViolationException ex) {
                // Con replicas que arrancan a la vez sobre una base vacia, otra ya inserto los clientes
                // (rut/email unicos): no es un error, la replica sigue arrancando.
                logger.info("Los clientes de ejemplo ya los cargo otra replica");
            }
        }
    }

    private Cliente crearCliente(String rut, String nombre, String apellido, String email,
                                 String telefono, String direccion, PerfilCliente perfil) {
        Cliente cliente = new Cliente();
        cliente.setRut(rut);
        cliente.setNombre(nombre);
        cliente.setApellido(apellido);
        cliente.setEmail(email);
        cliente.setTelefono(telefono);
        cliente.setDireccion(direccion);
        cliente.setFechaRegistro(LocalDateTime.now());
        cliente.setEstado(EstadoCliente.ACTIVO);
        cliente.setPerfil(perfil);
        return cliente;
    }
}
