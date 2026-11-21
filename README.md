# CourtSync Pro — MVP (Spec-Driven Development)

MVP funcional del **Sistema de Reserva de Canchas Deportivas** construido bajo el enfoque **Spec-Driven Development** (Hito 1 y Hito 2) con **Java 21 + Spring Boot 3.4**, **PostgreSQL 16**, **React 18 + Vite + TypeScript**, **Docker** y **GitHub Actions**.

## Alcance del MVP
- **`HU-01` Consultar Disponibilidad:** `GET /api/v1/canchas/disponibilidad?fecha=YYYY-MM-DD&tipoCancha=PADEL` (franjas de 1h entre `08:00` y `22:00` UTC; rechaza fechas pasadas con HTTP `400`).
- **`HU-02` Crear Reserva:** `POST /api/v1/reservas`
  - **`RN-01`:** Máximo 2 reservas activas por jugador en la misma fecha (`422`).
  - **`RN-02`:** Mínimo 2 horas de anticipación (`422`).
  - **`RN-03`:** Estado inicial `PENDIENTE_PAGO` con expiración de 10 minutos.
  - **`RN-09`:** `valorTotal = tarifaHora * duracionHoras` y `valorAnticipo = 50%`.
  - **`RNF-02`:** Bloqueo pesimista (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) sobre `Cancha` (`409 CONFLICT_HORARIO_OCUPADO`).
  - **`RNF-04`:** Evento `ReservaCreadaEvent` procesado con `@Async` y `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`.

---

## 1. Ejecutar pruebas automatizadas

### Backend (JUnit 5 + Mockito + MockMvc)
```bash
cd backend
mvn test
```

Suites del backend (patrón AAA):
- `ReservaServiceTest` — unitarias del servicio (JUnit 5 + Mockito): camino feliz, RN-01, RN-02 (1h59 vs 2h01), RNF-02 (409), 404 y eventos (RNF-04).
- `ReservaControllerIntegrationTest` — `@WebMvcTest` + `MockMvc`: 201, 400 (`@NotNull`/`@Min`/`@Max`), 409 y 422 con `ErrorResponse`.
- `ReservaRepositoryTest` — `@DataJpaTest` sobre H2: solapamiento de horarios, contiguos, canceladas/expiradas y conteo diario.

### Frontend (TypeScript + Vite Build)
```bash
cd frontend
npm ci
npm run build
```

---

## 2. Levantar todo el stack con Docker

```bash
cp .env.example .env
docker compose up --build -d
```

- **Frontend (Nginx):** http://localhost
- **Backend Healthcheck:** http://localhost:8080/actuator/health
- **PostgreSQL:** `localhost:5432`

---

## 3. Probar endpoints rápidamente (`curl`)

### Consultar disponibilidad (`HU-01`)
```bash
curl "http://localhost:8080/api/v1/canchas/disponibilidad?fecha=2026-11-25&tipoCancha=PADEL"
```

### Crear reserva (`HU-02`)
```bash
curl -X POST "http://localhost:8080/api/v1/reservas" \
  -H "Content-Type: application/json" \
  -H "X-Usuario-Id: 1" \
  -d '{
    "canchaId": 1,
    "fechaHoraInicio": "2026-11-25T19:00:00Z",
    "duracionHoras": 1
  }'
```
