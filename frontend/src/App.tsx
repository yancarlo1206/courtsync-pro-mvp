import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ApiError,
  consultarDisponibilidad,
  crearReserva,
  DisponibilidadCanchaResponse,
  FranjaHorariaDTO,
  ReservaResponse,
  TipoCancha,
} from './services/reservaApi';

interface SlotSeleccionado {
  cancha: DisponibilidadCanchaResponse;
  franja: FranjaHorariaDTO;
}

function getTodayLocalIso(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function toLocalOffsetIso(fecha: string, hora: string): string {
  const localDate = new Date(`${fecha}T${hora}:00`);
  const offsetMinutes = -localDate.getTimezoneOffset();
  const sign = offsetMinutes >= 0 ? '+' : '-';
  const absMinutes = Math.abs(offsetMinutes);
  const offsetHours = String(Math.floor(absMinutes / 60)).padStart(2, '0');
  const offsetMins = String(absMinutes % 60).padStart(2, '0');
  return `${fecha}T${hora}:00${sign}${offsetHours}:${offsetMins}`;
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('es-CO', {
    style: 'currency',
    currency: 'COP',
    maximumFractionDigits: 0,
  }).format(value);
}

function formatCountdown(secondsLeft: number): string {
  const safe = Math.max(0, secondsLeft);
  const mins = Math.floor(safe / 60);
  const secs = safe % 60;
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

export function App() {
  const [fecha, setFecha] = useState<string>(getTodayLocalIso);
  const [tipoCancha, setTipoCancha] = useState<TipoCancha | ''>('');
  const [canchas, setCanchas] = useState<DisponibilidadCanchaResponse[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [errorState, setErrorState] = useState<{ status: number; message: string } | null>(null);

  const [slotSeleccionado, setSlotSeleccionado] = useState<SlotSeleccionado | null>(null);
  const [duracionHoras, setDuracionHoras] = useState<1 | 2>(1);
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  const [reservaConfirmada, setReservaConfirmada] = useState<ReservaResponse | null>(null);
  const [segundosRestantes, setSegundosRestantes] = useState<number>(600);

  const cargarDisponibilidad = useCallback(async () => {
    setIsLoading(true);
    setErrorState(null);
    try {
      const data = await consultarDisponibilidad(fecha, tipoCancha);
      setCanchas(data);
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorState({ status: err.status, message: err.message });
      } else {
        setErrorState({ status: 500, message: 'Error de conexión con el servidor.' });
      }
      setCanchas([]);
    } finally {
      setIsLoading(false);
    }
  }, [fecha, tipoCancha]);

  useEffect(() => {
    void cargarDisponibilidad();
  }, [cargarDisponibilidad]);

  // Live 10-minute countdown timer for Confirmation State (RN-03)
  useEffect(() => {
    if (!reservaConfirmada) return;

    const calcularSegundos = () => {
      const expiraMs = new Date(reservaConfirmada.fechaExpiracionPago).getTime();
      return Math.max(0, Math.floor((expiraMs - Date.now()) / 1000));
    };

    setSegundosRestantes(calcularSegundos());
    const intervalId = window.setInterval(() => {
      setSegundosRestantes(calcularSegundos());
    }, 1000);

    return () => window.clearInterval(intervalId);
  }, [reservaConfirmada]);

  const calculosModal = useMemo(() => {
    if (!slotSeleccionado) {
      return { valorTotal: 0, valorAnticipo: 0 };
    }
    const valorTotal = slotSeleccionado.cancha.tarifaHora * duracionHoras;
    const valorAnticipo = valorTotal * 0.5;
    return { valorTotal, valorAnticipo };
  }, [slotSeleccionado, duracionHoras]);

  const abrirModalReserva = (cancha: DisponibilidadCanchaResponse, franja: FranjaHorariaDTO) => {
    setErrorState(null);
    setDuracionHoras(1);
    setSlotSeleccionado({ cancha, franja });
  };

  const handleConfirmarReserva = async () => {
    if (!slotSeleccionado || isSubmitting) return;

    setIsSubmitting(true);
    setErrorState(null);

    try {
      const fechaHoraInicio = toLocalOffsetIso(fecha, slotSeleccionado.franja.horaInicio);
      const reserva = await crearReserva({
        canchaId: slotSeleccionado.cancha.canchaId,
        fechaHoraInicio,
        duracionHoras,
      });

      setSlotSeleccionado(null);
      setReservaConfirmada(reserva);
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorState({ status: err.status, message: err.message });
        if (err.status === 409) {
          setSlotSeleccionado(null);
          void cargarDisponibilidad();
        }
      } else {
        setErrorState({ status: 500, message: 'No fue posible procesar la reserva.' });
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  const volverAGrilla = () => {
    setReservaConfirmada(null);
    void cargarDisponibilidad();
  };

  return (
    <div className="container">
      <header className="header">
        <div>
          <h1>CourtSync Pro</h1>
          <p>Reserva de canchas deportivas con bloqueo temporal e integridad transaccional</p>
        </div>
      </header>

      {/* Estado 3: Error State (Banner rojo 409 / Alerta ámbar 422 o 400) */}
      {errorState && (
        <div
          className={`alert ${errorState.status === 409 ? 'alert-409' : 'alert-422'}`}
          role="alert"
        >
          <div>
            <span className="alert-title">
              {errorState.status === 409
                ? 'Conflicto de horario (409 Conflict)'
                : `Validación de regla (${errorState.status})`}
            </span>
            <span>{errorState.message}</span>
          </div>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => setErrorState(null)}
          >
            Cerrar
          </button>
        </div>
      )}

      {/* Estado 4: Confirmation State (201 Created) */}
      {reservaConfirmada ? (
        <section className="confirmation-card" aria-live="polite">
          <span className="badge badge-pending">{reservaConfirmada.estado}</span>
          <div className="confirmation-code">{reservaConfirmada.codigoConfirmacion}</div>
          <p>
            Tu turno ha sido bloqueado temporalmente en{' '}
            <strong>{reservaConfirmada.canchaNombre}</strong>.
          </p>

          <div className="summary-box">
            <div className="summary-row">
              <span>Fecha y hora:</span>
              <strong>
                {new Date(reservaConfirmada.fechaHoraInicio).toLocaleString('es-CO', {
                  dateStyle: 'medium',
                  timeStyle: 'short',
                })}
              </strong>
            </div>
            <div className="summary-row">
              <span>Duración:</span>
              <strong>{reservaConfirmada.duracionHoras} hora(s)</strong>
            </div>
            <div className="summary-row">
              <span>Valor total:</span>
              <strong>{formatCurrency(reservaConfirmada.valorTotal)}</strong>
            </div>
            <div className="summary-row highlight">
              <span>Anticipo requerido (50%):</span>
              <strong>{formatCurrency(reservaConfirmada.valorAnticipo)}</strong>
            </div>
          </div>

          <div className="countdown-box">
            <div>Tiempo restante para confirmar el pago del anticipo (RN-03)</div>
            <div className="countdown-timer">{formatCountdown(segundosRestantes)}</div>
          </div>

          <button type="button" className="btn btn-primary" onClick={volverAGrilla}>
            Consultar más horarios
          </button>
        </section>
      ) : (
        <>
          <section className="filters-card">
            <div className="field">
              <label htmlFor="fecha-input">Fecha</label>
              <input
                id="fecha-input"
                type="date"
                value={fecha}
                min={getTodayLocalIso()}
                disabled={isLoading}
                onChange={(e) => setFecha(e.target.value)}
              />
            </div>

            <div className="field">
              <label htmlFor="tipo-select">Tipo de Cancha</label>
              <select
                id="tipo-select"
                value={tipoCancha}
                disabled={isLoading}
                onChange={(e) => setTipoCancha(e.target.value as TipoCancha | '')}
              >
                <option value="">Todas</option>
                <option value="PADEL">Pádel</option>
                <option value="FUTBOL_5">Fútbol 5</option>
                <option value="TENIS">Tenis</option>
              </select>
            </div>

            <button
              type="button"
              className="btn btn-primary"
              disabled={isLoading}
              onClick={() => void cargarDisponibilidad()}
            >
              {isLoading ? 'Consultando...' : 'Actualizar'}
            </button>
          </section>

          {/* Estado 1: Loading State */}
          {isLoading ? (
            <section className="skeleton-grid" aria-label="Cargando disponibilidad">
              {[1, 2, 3].map((item) => (
                <div key={item} className="skeleton-card">
                  <div className="skeleton-line short" />
                  <div className="skeleton-line" />
                  <div className="skeleton-line tall" />
                </div>
              ))}
            </section>
          ) : canchas.length === 0 ? (
            <section className="empty-state">
              <p>No hay horarios disponibles para la fecha o filtro seleccionado.</p>
            </section>
          ) : (
            /* Estado 2: Success / Data State */
            <section className="courts-grid">
              {canchas.map((cancha) => (
                <article key={cancha.canchaId} className="court-card">
                  <div className="court-header">
                    <div>
                      <h2 className="court-title">{cancha.nombre}</h2>
                      <div className="court-meta">
                        Tarifa: {formatCurrency(cancha.tarifaHora)} / hora
                      </div>
                    </div>
                    <span className="badge badge-type">{cancha.tipoCancha}</span>
                  </div>

                  <div className="slots-grid">
                    {cancha.franjas.map((franja) => {
                      const isLibre = franja.estado === 'LIBRE';
                      const slotClass =
                        franja.estado === 'LIBRE'
                          ? 'slot-libre'
                          : franja.estado === 'MANTENIMIENTO'
                            ? 'slot-mantenimiento'
                            : 'slot-ocupado';

                      return (
                        <button
                          key={franja.horaInicio}
                          type="button"
                          className={`slot-btn ${slotClass}`}
                          disabled={!isLibre}
                          onClick={() => abrirModalReserva(cancha, franja)}
                        >
                          {franja.horaInicio} - {franja.horaFin}
                          <br />
                          <small>{franja.estado}</small>
                        </button>
                      );
                    })}
                  </div>
                </article>
              ))}
            </section>
          )}
        </>
      )}

      {/* Modal de Confirmación de Reserva */}
      {slotSeleccionado && (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <div className="modal">
            <h2>Confirmar Reserva</h2>
            <p>{slotSeleccionado.cancha.nombre}</p>

            {errorState && errorState.status === 422 && (
              <div className="alert alert-422" style={{ marginTop: '0.75rem' }}>
                <span>{errorState.message}</span>
              </div>
            )}

            <div className="field" style={{ marginTop: '1rem' }}>
              <label htmlFor="duracion-select">Duración (horas)</label>
              <select
                id="duracion-select"
                value={duracionHoras}
                disabled={isSubmitting}
                onChange={(e) => setDuracionHoras(Number(e.target.value) as 1 | 2)}
              >
                <option value={1}>1 hora</option>
                <option value={2}>2 horas</option>
              </select>
            </div>

            <div className="summary-box">
              <div className="summary-row">
                <span>Inicio:</span>
                <strong>
                  {fecha} {slotSeleccionado.franja.horaInicio}
                </strong>
              </div>
              <div className="summary-row">
                <span>Valor total:</span>
                <strong>{formatCurrency(calculosModal.valorTotal)}</strong>
              </div>
              <div className="summary-row highlight">
                <span>Anticipo 50% (RN-09):</span>
                <strong>{formatCurrency(calculosModal.valorAnticipo)}</strong>
              </div>
            </div>

            <div className="modal-actions">
              <button
                type="button"
                className="btn btn-secondary"
                disabled={isSubmitting}
                onClick={() => setSlotSeleccionado(null)}
              >
                Cancelar
              </button>
              <button
                type="button"
                className="btn btn-primary"
                disabled={isSubmitting}
                onClick={() => void handleConfirmarReserva()}
              >
                {isSubmitting ? 'Procesando...' : 'Confirmar Reserva'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
