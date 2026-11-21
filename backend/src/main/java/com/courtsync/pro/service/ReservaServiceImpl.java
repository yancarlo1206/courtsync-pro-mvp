package com.courtsync.pro.service;

import com.courtsync.pro.domain.entity.Cancha;
import com.courtsync.pro.domain.entity.EstadoReserva;
import com.courtsync.pro.domain.entity.Reserva;
import com.courtsync.pro.dto.CrearReservaRequest;
import com.courtsync.pro.dto.ReservaResponse;
import com.courtsync.pro.exception.HorarioNoDisponibleException;
import com.courtsync.pro.exception.RecursoNoEncontradoException;
import com.courtsync.pro.exception.ReglaNegocioException;
import com.courtsync.pro.repository.CanchaRepository;
import com.courtsync.pro.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservaServiceImpl implements ReservaService {

        private static final int ANTICIPACION_MINIMA_HORAS = 2;
        private static final int MAX_RESERVAS_ACTIVAS_POR_DIA = 2;
        private static final int EXPIRACION_PAGO_MINUTOS = 10;
        private static final double PORCENTAJE_ANTICIPO = 0.5;

        private final CanchaRepository canchaRepository;
        private final ReservaRepository reservaRepository;
        private final ApplicationEventPublisher eventPublisher;

        @Override
        @Transactional
        public ReservaResponse crearReserva(CrearReservaRequest request, Long usuarioId) {
                OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
                OffsetDateTime inicio = request.fechaHoraInicio().withOffsetSameInstant(ZoneOffset.UTC);
                OffsetDateTime fin = inicio.plusHours(request.duracionHoras());

                // RN-02: Mínimo 2 horas de anticipación respecto a fechaHoraInicio
                if (Duration.between(ahora, inicio).toMinutes() < ANTICIPACION_MINIMA_HORAS * 60L) {
                        throw new ReglaNegocioException(
                                        "Solo se permiten reservas con mínimo 2 horas de anticipación (RN-02).",
                                        "UNPROCESSABLE_ANTICIPACION_MINIMA");
                }

                // RN-01: Máximo 2 reservas activas por jugador en la misma fecha calendario
                LocalDate fechaReserva = inicio.toLocalDate();
                OffsetDateTime inicioDia = fechaReserva.atStartOfDay().atOffset(ZoneOffset.UTC);
                OffsetDateTime finDia = fechaReserva.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

                long reservasActivasUsuario = reservaRepository.countReservasActivasPorUsuarioEnFecha(
                                usuarioId, inicioDia, finDia, ahora);
                if (reservasActivasUsuario >= MAX_RESERVAS_ACTIVAS_POR_DIA) {
                        throw new ReglaNegocioException(
                                        "El jugador ya alcanzó el límite máximo de 2 reservas activas para esta fecha (RN-01).",
                                        "UNPROCESSABLE_LIMITE_DIARIO");
                }

                // RNF-02: Bloqueo pesimista sobre la cancha para evitar sobreventa por
                // concurrencia
                Cancha cancha = canchaRepository.findByIdWithLock(request.canchaId())
                                .orElseThrow(() -> new RecursoNoEncontradoException(
                                                "No se encontró una cancha activa con id: " + request.canchaId()));

                List<Reserva> traslapes = reservaRepository.findReservasActivasEnRangoPorCancha(
                                cancha.getId(), inicio, fin, ahora);
                if (!traslapes.isEmpty()) {
                        throw new HorarioNoDisponibleException(
                                        "La " + cancha.getNombre() + " ya fue reservada para el horario seleccionado.");
                }

                // RN-09: Cálculo de tarifa total y anticipo del 50%
                double valorTotal = cancha.getTarifaHora() * request.duracionHoras();
                double valorAnticipo = valorTotal * PORCENTAJE_ANTICIPO;

                // RN-03: Estado inicial PENDIENTE_PAGO con expiración de 10 minutos
                OffsetDateTime fechaExpiracionPago = ahora.plusMinutes(EXPIRACION_PAGO_MINUTOS);
                String codigoTemporal = "RES-" + ahora.getYear() + "-"
                                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

                Reserva nuevaReserva = Reserva.builder()
                                .codigoConfirmacion(codigoTemporal)
                                .cancha(cancha)
                                .usuarioId(usuarioId)
                                .fechaHoraInicio(inicio)
                                .fechaHoraFin(fin)
                                .duracionHoras(request.duracionHoras())
                                .valorTotal(valorTotal)
                                .valorAnticipo(valorAnticipo)
                                .estado(EstadoReserva.PENDIENTE_PAGO)
                                .fechaCreacion(ahora)
                                .fechaExpiracionPago(fechaExpiracionPago)
                                .build();

                Reserva guardada = reservaRepository.save(nuevaReserva);
                if (guardada.getId() != null) {
                        guardada.setCodigoConfirmacion(String.format("RES-%d-%04d", ahora.getYear(), guardada.getId()));
                }

                // RNF-04: Evento asíncrono procesado AFTER_COMMIT
                eventPublisher.publishEvent(new ReservaCreadaEvent(
                                guardada.getId(),
                                guardada.getCodigoConfirmacion(),
                                guardada.getUsuarioId(),
                                cancha.getNombre(),
                                guardada.getFechaHoraInicio(),
                                guardada.getValorAnticipo(),
                                guardada.getFechaExpiracionPago()));

                return new ReservaResponse(
                                guardada.getId(),
                                guardada.getCodigoConfirmacion(),
                                cancha.getId(),
                                cancha.getNombre(),
                                guardada.getFechaHoraInicio(),
                                guardada.getDuracionHoras(),
                                guardada.getValorTotal(),
                                guardada.getValorAnticipo(),
                                guardada.getEstado(),
                                guardada.getFechaExpiracionPago());
        }
}
