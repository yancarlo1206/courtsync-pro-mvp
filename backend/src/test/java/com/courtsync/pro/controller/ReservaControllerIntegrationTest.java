package com.courtsync.pro.controller;

import com.courtsync.pro.domain.entity.EstadoReserva;
import com.courtsync.pro.dto.ReservaResponse;
import com.courtsync.pro.exception.GlobalExceptionHandler;
import com.courtsync.pro.exception.HorarioNoDisponibleException;
import com.courtsync.pro.exception.ReglaNegocioException;
import com.courtsync.pro.service.ReservaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservaController.class)
@Import(GlobalExceptionHandler.class)
class ReservaControllerIntegrationTest {

    private static final String URL = "/api/v1/reservas";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservaService reservaService;

    private static final String JSON_VALIDO = """
            {
              "canchaId": 1,
              "fechaHoraInicio": "2026-11-25T19:00:00Z",
              "duracionHoras": 1
            }
            """;

    // ---------------------------------------------------------------
    // 201 Created: ruta y formato JSON de respuesta
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Debe retornar HTTP 201 con el JSON de la reserva cuando la solicitud es válida")
    void crearReserva_Valida_Retorna201() throws Exception {
        // Arrange
        OffsetDateTime inicio = OffsetDateTime.of(2026, 11, 25, 19, 0, 0, 0, ZoneOffset.UTC);
        ReservaResponse respuesta = new ReservaResponse(
                504L, "RES-2026-0504", 1L, "Cancha Pádel 1 - Panorámica",
                inicio, 1, 80000.0, 40000.0, EstadoReserva.PENDIENTE_PAGO, inicio.minusHours(3));
        when(reservaService.crearReserva(any(), eq(1L))).thenReturn(respuesta);

        // Act & Assert
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservaId").value(504))
                .andExpect(jsonPath("$.estado").value("PENDIENTE_PAGO"))
                .andExpect(jsonPath("$.valorTotal").value(80000.0))
                .andExpect(jsonPath("$.valorAnticipo").value(40000.0));
    }

    // ---------------------------------------------------------------
    // 400: validaciones @Valid (@NotNull, @Min, @Max)
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Debe retornar HTTP 400 cuando duracionHoras supera el máximo (@Max) sin llegar al servicio")
    void crearReserva_DuracionMayorAlMaximo_Retorna400() throws Exception {
        String json = """
                { "canchaId": 1, "fechaHoraInicio": "2026-11-25T19:00:00Z", "duracionHoras": 5 }
                """;

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST_VALIDACION"))
                .andExpect(jsonPath("$.message").value("La duración máxima es de 2 horas"))
                .andExpect(jsonPath("$.path").value(URL))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        verifyNoInteractions(reservaService);
    }

    @Test
    @DisplayName("Debe retornar HTTP 400 cuando duracionHoras es menor al mínimo (@Min) sin llegar al servicio")
    void crearReserva_DuracionMenorAlMinimo_Retorna400() throws Exception {
        String json = """
                { "canchaId": 1, "fechaHoraInicio": "2026-11-25T19:00:00Z", "duracionHoras": 0 }
                """;

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST_VALIDACION"))
                .andExpect(jsonPath("$.message").value("La duración mínima es de 1 hora"));

        verifyNoInteractions(reservaService);
    }

    @Test
    @DisplayName("Debe retornar HTTP 400 cuando faltan campos obligatorios (@NotNull) sin llegar al servicio")
    void crearReserva_CamposObligatoriosNulos_Retorna400() throws Exception {
        String json = "{}";

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST_VALIDACION"))
                .andExpect(jsonPath("$.message").value(containsString("canchaId es obligatorio")))
                .andExpect(jsonPath("$.message").value(containsString("fechaHoraInicio es obligatorio")))
                .andExpect(jsonPath("$.message").value(containsString("duracionHoras es obligatorio")));

        verifyNoInteractions(reservaService);
    }

    // ---------------------------------------------------------------
    // 409 y 422: mapeo de excepciones del servicio -> ErrorResponse
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Debe retornar HTTP 409 Conflict con ErrorResponse cuando el horario ya está ocupado")
    void crearReserva_HorarioOcupado_Retorna409ErrorResponse() throws Exception {
        when(reservaService.crearReserva(any(), eq(1L)))
                .thenThrow(new HorarioNoDisponibleException("La Cancha 1 ya fue reservada para el horario seleccionado."));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(JSON_VALIDO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CONFLICT_HORARIO_OCUPADO"))
                .andExpect(jsonPath("$.message").value("La Cancha 1 ya fue reservada para el horario seleccionado."))
                .andExpect(jsonPath("$.path").value(URL))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("Debe retornar HTTP 422 con ErrorResponse cuando se viola una regla de negocio (RN-01/RN-02)")
    void crearReserva_ReglaNegocio_Retorna422ErrorResponse() throws Exception {
        when(reservaService.crearReserva(any(), eq(1L)))
                .thenThrow(new ReglaNegocioException(
                        "Solo se permiten reservas con mínimo 2 horas de anticipación (RN-02).",
                        "UNPROCESSABLE_ANTICIPACION_MINIMA"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(JSON_VALIDO))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ANTICIPACION_MINIMA"))
                .andExpect(jsonPath("$.message").value(containsString("2 horas de anticipación")))
                .andExpect(jsonPath("$.path").value(URL))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
