package com.banco.security.config;

import com.banco.security.model.Usuario;
import com.banco.security.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (usuarioRepository.count() == 0) {
            try {
                usuarioRepository.save(crearUsuario("cliente.web", "ROLE_WEB", "ClienteWeb2026!"));
                usuarioRepository.save(crearUsuario("cliente.movil", "ROLE_MOVIL", "ClienteMovil2026!"));
                usuarioRepository.save(crearUsuario("cajero.central", "ROLE_CAJERO", "CajeroCentral2026!"));
            } catch (DataIntegrityViolationException ex) {
                // Con replicas que arrancan a la vez sobre una base vacia, otra ya inserto los usuarios
                // (username unico): no es un error, la replica sigue arrancando.
                logger.info("Los usuarios iniciales ya los cargo otra replica");
            }
        }
    }

    private Usuario crearUsuario(String username, String rol, String passwordPlano) {
        Usuario usuario = new Usuario();
        usuario.setUsername(username);
        usuario.setPassword(passwordEncoder.encode(passwordPlano));
        usuario.setRol(rol);
        return usuario;
    }
}
