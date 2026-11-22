package com.courtsync.pro.service;

import com.courtsync.pro.domain.entity.Cancha;
import com.courtsync.pro.domain.entity.Reserva;
import com.courtsync.pro.domain.entity.TipoCancha;
import com.courtsync.pro.dto.DisponibilidadCanchaResponse;
import com.courtsync.pro.dto.FranjaHorariaDTO;
import com.courtsync.pro.exception.FechaInvalidaException;
import com.courtsync.pro.repository.CanchaRepository;
import com.courtsync.pro.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CanchaServiceImpl implements CanchaService {

    private static final ZoneId ZONA_HORARIA = ZoneId.of("America/Bogota");
    private static final int HORA_APERTURA = 8;
    private static final int HORA_CIERRE = 23;
    private static final DateTimeFormatter HORA_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final CanchaRepository canchaRepository;
    private final ReservaRepository reservaRepository;

    @Override
    @Transactional(readOnly = true)
    public List<DisponibilidadCanchaResponse> consultarDisponibilidad(LocalDate fecha, TipoCancha tipoCancha) {
        LocalDate hoy = LocalDate.now(ZONA_HORARIA);
        if (fecha.isBefore(hoy)) {
            throw new FechaInvalidaException("No se puede consultar disponibilidad para una fecha pasada: " + fecha);
        }

        List<Cancha> canchas = tipoCancha != null
                ? canchaRepository.findByActivaTrueAndTipoCancha(tipoCancha)
                : canchaRepository.findByActivaTrue();

        if (canchas.isEmpty()) {
            return List.of();
        }

        OffsetDateTime ahora = OffsetDateTime.now(ZONA_HORARIA);
        OffsetDateTime inicioDia = fecha.atTime(HORA_APERTURA, 0).atZone(ZONA_HORARIA).toOffsetDateTime();
        OffsetDateTime finDia = fecha.atTime(HORA_CIERRE, 0).atZone(ZONA_HORARIA).toOffsetDateTime();

        List<Long> canchaIds = canchas.stream().map(Cancha::getId).toList();
        Map<Long, List<Reserva>> reservasPorCancha = reservaRepository
                .findReservasActivasPorCanchasEnFecha(canchaIds, inicioDia, finDia, ahora)
                .stream()
                .collect(Collectors.groupingBy(r -> r.getCancha().getId()));

        return canchas.stream()
                .map(cancha -> mapearDisponibilidad(cancha, fecha, reservasPorCancha.getOrDefault(cancha.getId(), List.of())))
                .toList();
    }

    private DisponibilidadCanchaResponse mapearDisponibilidad(
            Cancha cancha,
            LocalDate fecha,
            List<Reserva> reservas
    ) {
        List<FranjaHorariaDTO> franjas = new ArrayList<>();
        for (int hora = HORA_APERTURA; hora < HORA_CIERRE; hora++) {
            LocalTime inicioLocal = LocalTime.of(hora, 0);
            LocalTime finLocal = LocalTime.of(hora + 1, 0);
            OffsetDateTime inicioSlot = fecha.atTime(inicioLocal).atZone(ZONA_HORARIA).toOffsetDateTime();
            OffsetDateTime finSlot = fecha.atTime(finLocal).atZone(ZONA_HORARIA).toOffsetDateTime();

            boolean ocupado = reservas.stream()
                    .anyMatch(r -> r.getFechaHoraInicio().isBefore(finSlot) && r.getFechaHoraFin().isAfter(inicioSlot));

            franjas.add(new FranjaHorariaDTO(
                    inicioLocal.format(HORA_FORMATTER),
                    finLocal.format(HORA_FORMATTER),
                    ocupado ? "OCUPADO" : "LIBRE"
            ));
        }

        return new DisponibilidadCanchaResponse(
                cancha.getId(),
                cancha.getNombre(),
                cancha.getTipoCancha(),
                cancha.getTarifaHora(),
                franjas
        );
    }
}
