package com.courtsync.pro.repository;

import com.courtsync.pro.domain.entity.Cancha;
import com.courtsync.pro.domain.entity.EstadoReserva;
import com.courtsync.pro.domain.entity.Reserva;
import com.courtsync.pro.domain.entity.TipoCancha;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de las consultas @Query contra H2 (en memoria).
 * Se desactiva data.sql (sintaxis PostgreSQL) y se deja que Hibernate cree el esquema.
 */
@DataJpaTest(properties = {
        "spring.sql.init.mode=never",
        "spring.jpa.defer-datasource-initialization=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ReservaRepositoryTest {

    // Fecha fija y "ahora" fijo para que las pruebas sean deterministas
    private static final OffsetDateTime AHORA = utc(2030, 1, 15, 8, 0);

    @Autowired
    private ReservaRepository reservaRepository;

    @Autowired
    private CanchaRepository canchaRepository;

    @Autowired
    private TestEntityManager em;

    private Cancha cancha1;
    private Cancha cancha2;

    @BeforeEach
    void setUp() {
        cancha1 = em.persist(Cancha.builder()
                .nombre("Cancha 1").tipoCancha(TipoCancha.PADEL).tarifaHora(80000.0).activa(true).build());
        cancha2 = em.persist(Cancha.builder()
                .nombre("Cancha 2").tipoCancha(TipoCancha.TENIS).tarifaHora(95000.0).activa(true).build());
    }

    private static OffsetDateTime utc(int y, int mo, int d, int h, int mi) {
        return OffsetDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC);
    }

    private int contador = 0;

    private Reserva guardar(Cancha cancha, Long usuarioId, OffsetDateTime inicio, OffsetDateTime fin,
                            EstadoReserva estado, OffsetDateTime expiracion) {
        contador++;
        return em.persistAndFlush(Reserva.builder()
                .codigoConfirmacion("RES-TEST-" + contador)
                .cancha(cancha)
                .usuarioId(usuarioId)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(fin)
                .duracionHoras((int) java.time.Duration.between(inicio, fin).toHours())
                .valorTotal(160000.0)
                .valorAnticipo(80000.0)
                .estado(estado)
                .fechaCreacion(AHORA)
                .fechaExpiracionPago(expiracion)
                .build());
    }

    private Reserva confirmada(Cancha cancha, Long usuarioId, int horaInicio, int horaFin) {
        return guardar(cancha, usuarioId, utc(2030, 1, 15, horaInicio, 0), utc(2030, 1, 15, horaFin, 0),
                EstadoReserva.CONFIRMADA, AHORA.plusMinutes(10));
    }

    private List<Reserva> buscarSolapes(Cancha cancha, int horaInicio, int horaFin) {
        return reservaRepository.findReservasActivasEnRangoPorCancha(
                cancha.getId(), utc(2030, 1, 15, horaInicio, 0), utc(2030, 1, 15, horaFin, 0), AHORA);
    }

    // ---------------------------------------------------------------
    // Solapamiento de horarios
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Detecta cruce parcial: reserva 18:00-20:00 vs consulta 19:00-21:00")
    void solapamiento_CruceParcial_EncuentraReserva() {
        // Arrange
        Reserva existente = confirmada(cancha1, 1L, 18, 20);

        // Act
        List<Reserva> resultado = buscarSolapes(cancha1, 19, 21);

        // Assert
        assertThat(resultado).extracting(Reserva::getId).containsExactly(existente.getId());
    }

    @Test
    @DisplayName("Permite horario contiguo posterior: reserva 18:00-20:00 vs consulta 20:00-21:00")
    void solapamiento_ContiguoPosterior_NoEncuentraReserva() {
        // Arrange
        confirmada(cancha1, 1L, 18, 20);

        // Act
        List<Reserva> resultado = buscarSolapes(cancha1, 20, 21);

        // Assert
        assertThat(resultado).isEmpty();
    }

    @Test
    @DisplayName("Permite horario contiguo anterior: reserva 18:00-20:00 vs consulta 17:00-18:00")
    void solapamiento_ContiguoAnterior_NoEncuentraReserva() {
        // Arrange
        confirmada(cancha1, 1L, 18, 20);

        // Act
        List<Reserva> resultado = buscarSolapes(cancha1, 17, 18);

        // Assert
        assertThat(resultado).isEmpty();
    }

    @Test
    @DisplayName("Ignora reservas CANCELADA, CANCELADA_CON_PENALIDAD y EXPIRADA")
    void solapamiento_ReservasInactivas_SeIgnoran() {
        // Arrange
        guardar(cancha1, 1L, utc(2030, 1, 15, 18, 0), utc(2030, 1, 15, 20, 0),
                EstadoReserva.CANCELADA, AHORA.plusMinutes(10));
        guardar(cancha1, 2L, utc(2030, 1, 15, 18, 0), utc(2030, 1, 15, 20, 0),
                EstadoReserva.CANCELADA_CON_PENALIDAD, AHORA.plusMinutes(10));
        guardar(cancha1, 3L, utc(2030, 1, 15, 18, 0), utc(2030, 1, 15, 20, 0),
                EstadoReserva.EXPIRADA, AHORA.plusMinutes(10));

        // Act
        List<Reserva> resultado = buscarSolapes(cancha1, 19, 21);

        // Assert
        assertThat(resultado).isEmpty();
    }

    @Test
    @DisplayName("PENDIENTE_PAGO bloquea solo mientras no haya expirado su plazo de pago")
    void solapamiento_PendientePago_BloqueaSoloSiNoExpiro() {
        // Arrange
        Reserva vigente = guardar(cancha1, 1L, utc(2030, 1, 15, 18, 0), utc(2030, 1, 15, 19, 0),
                EstadoReserva.PENDIENTE_PAGO, AHORA.plusMinutes(5));
        guardar(cancha1, 2L, utc(2030, 1, 15, 19, 0), utc(2030, 1, 15, 20, 0),
                EstadoReserva.PENDIENTE_PAGO, AHORA.minusMinutes(1));

        // Act
        List<Reserva> resultado = buscarSolapes(cancha1, 18, 20);

        // Assert
        assertThat(resultado).extracting(Reserva::getId).containsExactly(vigente.getId());
    }

    @Test
    @DisplayName("No mezcla reservas de otra cancha")
    void solapamiento_OtraCancha_NoEncuentraReserva() {
        // Arrange
        confirmada(cancha2, 1L, 18, 20);

        // Act
        List<Reserva> resultado = buscarSolapes(cancha1, 18, 20);

        // Assert
        assertThat(resultado).isEmpty();
    }

    // ---------------------------------------------------------------
    // RN-01: conteo de reservas activas por jugador en el día
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Cuenta solo reservas activas del jugador dentro de la fecha consultada")
    void conteoPorUsuario_SoloActivasDelJugadorEnElDia() {
        // Arrange
        confirmada(cancha1, 1L, 10, 11);                                       // cuenta
        guardar(cancha2, 1L, utc(2030, 1, 15, 15, 0), utc(2030, 1, 15, 16, 0),
                EstadoReserva.PENDIENTE_PAGO, AHORA.plusMinutes(5));           // cuenta
        guardar(cancha1, 1L, utc(2030, 1, 15, 17, 0), utc(2030, 1, 15, 18, 0),
                EstadoReserva.CANCELADA, AHORA.plusMinutes(10));               // no cuenta
        guardar(cancha1, 1L, utc(2030, 1, 15, 19, 0), utc(2030, 1, 15, 20, 0),
                EstadoReserva.PENDIENTE_PAGO, AHORA.minusMinutes(1));          // no cuenta (expirada)
        guardar(cancha1, 1L, utc(2030, 1, 16, 10, 0), utc(2030, 1, 16, 11, 0),
                EstadoReserva.CONFIRMADA, AHORA.plusMinutes(10));              // otro día
        confirmada(cancha1, 2L, 12, 13);                                       // otro jugador

        // Act
        long total = reservaRepository.countReservasActivasPorUsuarioEnFecha(
                1L, utc(2030, 1, 15, 0, 0), utc(2030, 1, 16, 0, 0), AHORA);

        // Assert
        assertThat(total).isEqualTo(2L);
    }

    // ---------------------------------------------------------------
    // 404: findByIdWithLock solo retorna canchas activas
    // ---------------------------------------------------------------
    @Test
    @DisplayName("findByIdWithLock retorna la cancha activa y vacío para una cancha inactiva")
    void findByIdWithLock_SoloCanchasActivas() {
        // Arrange
        Cancha inactiva = em.persistAndFlush(Cancha.builder()
                .nombre("Cancha en mantenimiento").tipoCancha(TipoCancha.PADEL)
                .tarifaHora(70000.0).activa(false).build());

        // Act & Assert
        assertThat(canchaRepository.findByIdWithLock(cancha1.getId())).isPresent();
        assertThat(canchaRepository.findByIdWithLock(inactiva.getId())).isEmpty();
        assertThat(canchaRepository.findByIdWithLock(9999L)).isEmpty();
    }
}
