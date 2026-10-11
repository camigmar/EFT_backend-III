package com.banco.core.controller;

import com.banco.core.dto.ResolucionMovimientoDTO;
import com.banco.core.dto.ResolucionMovimientoRequestDTO;
import com.banco.core.service.CuentaService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

// Uso interno del reconciliador de servicio-pagos (token con scope "interno").
@RestController
@RequestMapping("/api/core/movimientos")
public class MovimientoController {

    private final CuentaService cuentaService;

    public MovimientoController(CuentaService cuentaService) {
        this.cuentaService = cuentaService;
    }

    // Dice si el movimiento con esta Idempotency-Key se aplico; si no, lo anula para siempre.
    @PostMapping("/{clave}/resolucion")
    public ResolucionMovimientoDTO resolver(@PathVariable String clave, @Valid @RequestBody ResolucionMovimientoRequestDTO request) {
        return cuentaService.resolverMovimiento(clave, request.cuentaId());
    }
}
