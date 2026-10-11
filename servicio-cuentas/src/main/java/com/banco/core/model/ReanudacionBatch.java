package com.banco.core.model;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

// Reclamo de la reanudacion de una ejecucion FAILED: la clave primaria es el id de esa ejecucion, asi que solo una
// replica (o un solo /reanudar) puede reanudarla. Se necesita porque, al reanudar una JobInstance existente, el
// JobRepository solo verifica que no haya otra ejecucion en curso, y dos replicas que verifican a la vez pasan ambas.
@Entity
@Table(name = "batch_reanudaciones")
public class ReanudacionBatch implements Persistable<Long> {

    @Id
    private Long ejecucionFallidaId;

    @Column(nullable = false)
    private String job;

    @Column(nullable = false)
    private String fecha;

    @Column(nullable = false)
    private LocalDateTime fechaReclamo;

    protected ReanudacionBatch() {
    }

    public ReanudacionBatch(Long ejecucionFallidaId, String job, String fecha) {
        this.ejecucionFallidaId = ejecucionFallidaId;
        this.job = job;
        this.fecha = fecha;
        this.fechaReclamo = LocalDateTime.now();
    }

    @Override
    public Long getId() {
        return ejecucionFallidaId;
    }

    // Siempre es nuevo: save() hace INSERT (persist) y no merge, asi un segundo reclamo de la misma ejecucion
    // falla por clave duplicada en vez de sobrescribir el primero.
    @Override
    public boolean isNew() {
        return true;
    }

    public String getJob() {
        return job;
    }

    public String getFecha() {
        return fecha;
    }

    public LocalDateTime getFechaReclamo() {
        return fechaReclamo;
    }
}
