package com.courtsync.pro.service;

import com.courtsync.pro.domain.entity.Cancha;
import com.courtsync.pro.domain.entity.EstadoReserva;
import com.courtsync.pro.domain.entity.Reserva;
import com.courtsync.pro.domain.entity.TipoCancha;
import com.courtsync.pro.dto.CrearReservaRequest;
import com.courtsync.pro.dto.ReservaResponse;
import com.courtsync.pro.exception.HorarioNoDisponibleException;
import com.courtsync.pro.exception.RecursoNoEncontradoException;
import com.courtsync.pro.exception.ReglaNegocioException;
import com.courtsync.pro.repository.CanchaRepository;
import com.courtsync.pro.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservaServiceTest {

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ReservaServiceImpl reservaService;

    private Cancha canchaPadel() {
        return Cancha.builder()
                .id(1L)
                .nombre("Cancha Pádel 1 - Panorámica")
                .tipoCancha(TipoCancha.PADEL)
                .tarifaHora(80000.0)
                .activa(true)
                .build();
    }

    // ---------------------------------------------------------------
    // Camino feliz (201): RN-03 + RN-09 + RNF-04
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Debe crear reserva en estado PENDIENTE_PAGO calculando 50% de anticipo (RN-03, RN-09)")
    void crearReserva_Exitosa_CalculaAnticipoYEstadoPendientePago() {
        // Arrange
        Long usuarioId = 1L;
        OffsetDateTime inicioFuturo = OffsetDateTime.now(ZoneOffset.UTC).plusHours(5);
        CrearReservaRequest request = new CrearReservaRequest(1L, inicioFuturo, 2);

        when(reservaRepository.countReservasActivasPorUsuarioEnFecha(eq(usuarioId), any(), any(), any()))
                .thenReturn(0L);
        when(canchaRepository.findByIdWithLock(1L)).thenReturn(Optional.of(canchaPadel()));
        when(reservaRepository.findReservasActivasEnRangoPorCancha(eq(1L), any(), any(), any()))
                .thenReturn(List.of());
        when(reservaRepository.save(any(Reserva.class))).thenAnswer(invocation -> {
            Reserva r = invocation.getArgument(0);
            r.setId(504L);
            return r;
        });

        // Act
        ReservaResponse response = reservaService.crearReserva(request, usuarioId);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.reservaId()).isEqualTo(504L);
        assertThat(response.estado()).isEqualTo(EstadoReserva.PENDIENTE_PAGO);
        assertThat(response.valorTotal()).isEqualTo(160000.0);
        assertThat(response.valorAnticipo()).isEqualTo(80000.0);
        assertThat(response.fechaExpiracionPago()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(9));
        assertThat(response.codigoConfirmacion()).endsWith("-0504");

        ArgumentCaptor<ReservaCreadaEvent> eventCaptor = ArgumentCaptor.forClass(ReservaCreadaEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().valorAnticipo()).isEqualTo(80000.0);
        assertThat(eventCaptor.getValue().reservaId()).isEqualTo(504L);
    }

    // ---------------------------------------------------------------
    // RN-01: límite de 2 reservas activas por día (422)
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Debe rechazar reserva cuando el jugador ya tiene 2 reservas activas en la fecha (RN-01)")
    void crearReserva_LimiteDiarioAlcanzado_LanzaReglaNegocioExceptionSinGuardar() {
        // Arrange
        Long usuarioId = 1L;
        OffsetDateTime inicioFuturo = OffsetDateTime.now(ZoneOffset.UTC).plusHours(6);
        CrearReservaRequest request = new CrearReservaRequest(1L, inicioFuturo, 1);

        when(reservaRepository.countReservasActivasPorUsuarioEnFecha(eq(usuarioId), any(), any(), any()))
                .thenReturn(2L);

        // Act & Assert
        assertThatThrownBy(() -> reservaService.crearReserva(request, usuarioId))
                .isInstanceOf(ReglaNegocioException.class)
                .hasMessageContaining("límite máximo de 2 reservas")
                .extracting("errorCode").isEqualTo("UNPROCESSABLE_LIMITE_DIARIO");

        verify(canchaRepository, never()).findByIdWithLock(any());
        verify(reservaRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ReservaCreadaEvent.class));
    }

    // ---------------------------------------------------------------
    // RN-02: anticipación mínima, valores límite (1h59 vs 2h01)
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Debe rechazar reserva con 1 hora y 59 minutos de anticipación (RN-02)")
    void crearReserva_UnaHoraCincuentaYNueveMinutos_LanzaReglaNegocioException() {
        // Arrange
        Long usuarioId = 1L;
        OffsetDateTime inicio = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).plusMinutes(59);
        CrearReservaRequest request = new CrearReservaRequest(1L, inicio, 1);

        // Act & Assert
        assertThatThrownBy(() -> reservaService.crearReserva(request, usuarioId))
                .isInstanceOf(ReglaNegocioException.class)
                .hasMessageContaining("2 horas")
                .extracting("errorCode").isEqualTo("UNPROCESSABLE_ANTICIPACION_MINIMA");

        verify(reservaRepository, never()).save(any());
        verify(canchaRepository, never()).findByIdWithLock(any());
        verify(eventPublisher, never()).publishEvent(any(ReservaCreadaEvent.class));
    }

    @Test
    @DisplayName("Debe aceptar reserva con 2 horas y 1 minuto de anticipación (RN-02)")
    void crearReserva_DosHorasUnMinuto_SeCreaCorrectamente() {
        // Arrange
        Long usuarioId = 1L;
        OffsetDateTime inicio = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).plusMinutes(1);
        CrearReservaRequest request = new CrearReservaRequest(1L, inicio, 1);

        when(reservaRepository.countReservasActivasPorUsuarioEnFecha(eq(usuarioId), any(), any(), any()))
                .thenReturn(0L);
        when(canchaRepository.findByIdWithLock(1L)).thenReturn(Optional.of(canchaPadel()));
        when(reservaRepository.findReservasActivasEnRangoPorCancha(eq(1L), any(), any(), any()))
                .thenReturn(List.of());
        when(reservaRepository.save(any(Reserva.class))).thenAnswer(inv -> {
            Reserva r = inv.getArgument(0);
            r.setId(10L);
            return r;
        });

        // Act
        ReservaResponse response = reservaService.crearReserva(request, usuarioId);

        // Assert
        assertThat(response.estado()).isEqualTo(EstadoReserva.PENDIENTE_PAGO);
        assertThat(response.valorTotal()).isEqualTo(80000.0);
        assertThat(response.valorAnticipo()).isEqualTo(40000.0);
        verify(reservaRepository, times(1)).save(any(Reserva.class));
    }

    // ---------------------------------------------------------------
    // RNF-02: choque de disponibilidad (409)
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Debe lanzar HorarioNoDisponibleException sin guardar ni publicar evento cuando hay choque (RNF-02)")
    void crearReserva_ChoqueDeHorario_LanzaHorarioNoDisponibleExceptionYNoGuarda() {
        // Arrange
        Long usuarioId = 1L;
        OffsetDateTime inicioFuturo = OffsetDateTime.now(ZoneOffset.UTC).plusHours(4);
        CrearReservaRequest request = new CrearReservaRequest(1L, inicioFuturo, 1);
        Cancha cancha = canchaPadel();

        Reserva reservaExistente = Reserva.builder()
                .id(99L)
                .cancha(cancha)
                .estado(EstadoReserva.CONFIRMADA)
                .fechaHoraInicio(inicioFuturo)
                .fechaHoraFin(inicioFuturo.plusHours(1))
                .build();

        when(reservaRepository.countReservasActivasPorUsuarioEnFecha(eq(usuarioId), any(), any(), any()))
                .thenReturn(0L);
        when(canchaRepository.findByIdWithLock(1L)).thenReturn(Optional.of(cancha));
        when(reservaRepository.findReservasActivasEnRangoPorCancha(eq(1L), any(), any(), any()))
                .thenReturn(List.of(reservaExistente));

        // Act & Assert
        assertThatThrownBy(() -> reservaService.crearReserva(request, usuarioId))
                .isInstanceOf(HorarioNoDisponibleException.class)
                .hasMessageContaining("ya fue reservada")
                .extracting("errorCode").isEqualTo("CONFLICT_HORARIO_OCUPADO");

        verify(reservaRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ReservaCreadaEvent.class));
    }

    // ---------------------------------------------------------------
    // 404: cancha inexistente o inactiva
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Debe lanzar RecursoNoEncontradoException cuando la cancha no existe o está inactiva (404)")
    void crearReserva_CanchaInexistenteOInactiva_LanzaRecursoNoEncontradoException() {
        // Arrange
        Long usuarioId = 1L;
        OffsetDateTime inicioFuturo = OffsetDateTime.now(ZoneOffset.UTC).plusHours(4);
        CrearReservaRequest request = new CrearReservaRequest(999L, inicioFuturo, 1);

        when(reservaRepository.countReservasActivasPorUsuarioEnFecha(eq(usuarioId), any(), any(), any()))
                .thenReturn(0L);
        // findByIdWithLock filtra "activa = true": una cancha inactiva también retorna vacío
        when(canchaRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> reservaService.crearReserva(request, usuarioId))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessageContaining("999")
                .extracting("errorCode").isEqualTo("NOT_FOUND_RECURSO");

        verify(reservaRepository, never()).findReservasActivasEnRangoPorCancha(any(), any(), any(), any());
        verify(reservaRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ReservaCreadaEvent.class));
    }
}
