package com.banco.core.controller;

import com.banco.core.dto.EjecucionBatchDTO;
import com.banco.core.exception.EjecucionBatchDuplicadaException;
import com.banco.core.exception.InstanciaBatchNoEncontradaException;
import com.banco.core.service.BatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Codigos HTTP del endpoint de reanudacion (sin contexto de Spring: MockMvc sobre el controlador).
class BatchControllerTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 10, 11);

    private BatchService batchService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        batchService = mock(BatchService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new BatchController(batchService)).build();
    }

    @Test
    void reanudar_jobFallido_responde200ConLaNuevaEjecucion() throws Exception {
        when(batchService.reanudar("transaccionesJob", FECHA))
                .thenReturn(new EjecucionBatchDTO("transaccionesJob", "2026-10-11", 2L, "COMPLETED", "COMPLETED", 0));

        mockMvc.perform(post("/api/core/batch/transaccionesJob/reanudar").param("fecha", "2026-10-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ejecucionId").value(2))
                .andExpect(jsonPath("$.estado").value("COMPLETED"));
    }

    @Test
    void reanudar_sinEjecuciones_responde404() throws Exception {
        when(batchService.reanudar("transaccionesJob", FECHA))
                .thenThrow(new InstanciaBatchNoEncontradaException("El job transaccionesJob no se ha ejecutado para la fecha 2026-10-11"));

        mockMvc.perform(post("/api/core/batch/transaccionesJob/reanudar").param("fecha", "2026-10-11"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").exists());
    }

    @Test
    void reanudar_completadoOEnEjecucion_responde409() throws Exception {
        when(batchService.reanudar("transaccionesJob", FECHA))
                .thenThrow(new EjecucionBatchDuplicadaException("El job transaccionesJob ya se completo para la fecha 2026-10-11"));

        mockMvc.perform(post("/api/core/batch/transaccionesJob/reanudar").param("fecha", "2026-10-11"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("El job transaccionesJob ya se completo para la fecha 2026-10-11"));
    }
}
