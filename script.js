const defaultConfig = {
  rows: ["A", "B", "C", "D", "E", "F", "G", "H"],
  seatsPerRow: 12,
  premiumRows: ["C", "D", "E"],
  maxSelection: 6,
  occupiedSeats: [],
  aisleAfter: [4, 8],
};

let config = { ...defaultConfig };
const seatingContainer = document.getElementById("seating");
const selectedSeatsLabel = document.getElementById("selected-seats");
const selectedCountLabel = document.getElementById("selected-count");
const reserveButton = document.getElementById("reserve");
const availableCountLabel = document.getElementById("available-count");
const clearSelectionButton = document.getElementById("clear-selection");
const statusMessage = document.getElementById("status-message");
const configForm = document.getElementById("config-form");
const rowsInput = document.getElementById("rows-input");
const seatsInput = document.getElementById("seats-input");
const premiumInput = document.getElementById("premium-input");
const maxInput = document.getElementById("max-input");
const occupiedInput = document.getElementById("occupied-input");

const selectedSeats = new Map();
let occupiedSeats = new Set();

const createSeatButton = (seatId, isPremium, isOccupied) => {
  const button = document.createElement("button");
  button.className = "seat-button";
  button.type = "button";
  button.textContent = seatId.slice(1);
  button.dataset.seat = seatId;
  button.setAttribute(
    "aria-label",
    `Asiento ${seatId} ${isPremium ? "premium" : "estándar"}`
  );

  if (isPremium) {
    button.classList.add("premium");
  }

  if (isOccupied) {
    button.classList.add("occupied");
    button.disabled = true;
  }

  button.addEventListener("click", () => toggleSeat(button, isPremium));

  return button;
};

const renderSeating = () => {
  seatingContainer.innerHTML = "";
  config.rows.forEach((row) => {
    const premium = config.premiumRows.includes(row);
    const rowWrapper = document.createElement("div");
    rowWrapper.className = "row";

    const leftLabel = document.createElement("span");
    leftLabel.className = "row-label";
    leftLabel.textContent = row;

    const rightLabel = document.createElement("span");
    rightLabel.className = "row-label";
    rightLabel.textContent = row;

    rowWrapper.appendChild(leftLabel);

    for (let seatNumber = 1; seatNumber <= config.seatsPerRow; seatNumber += 1) {
      const seatId = `${row}${seatNumber}`;
      const button = createSeatButton(seatId, premium, occupiedSeats.has(seatId));
      rowWrapper.appendChild(button);

      if (config.aisleAfter.includes(seatNumber)) {
        const spacer = document.createElement("span");
        spacer.className = "seat-spacer";
        rowWrapper.appendChild(spacer);
      }
    }

    rowWrapper.appendChild(rightLabel);
    seatingContainer.appendChild(rowWrapper);
  });

  updateSummary();
};

const toggleSeat = (button, isPremium) => {
  const seatId = button.dataset.seat;

  if (selectedSeats.has(seatId)) {
    selectedSeats.delete(seatId);
    button.classList.remove("selected");
    statusMessage.textContent = "";
  } else {
    if (selectedSeats.size >= config.maxSelection) {
      statusMessage.textContent = `Solo puedes seleccionar hasta ${config.maxSelection} asientos.`;
      return;
    }
    selectedSeats.set(seatId, isPremium ? "premium" : "estándar");
    button.classList.add("selected");
    statusMessage.textContent = "";
  }

  updateSummary();
};

const updateSummary = () => {
  const selectedSeatList = Array.from(selectedSeats.keys()).sort();
  const count = selectedSeatList.length;

  selectedSeatsLabel.textContent = count ? selectedSeatList.join(", ") : "Ninguno";
  selectedCountLabel.textContent = count.toString();
  reserveButton.disabled = count === 0;

  const totalAvailable = config.rows.length * config.seatsPerRow - occupiedSeats.size;
  availableCountLabel.textContent = (totalAvailable - count).toString();
};

const clearSelection = () => {
  selectedSeats.forEach((_, seatId) => {
    const button = document.querySelector(`[data-seat="${seatId}"]`);
    if (button) {
      button.classList.remove("selected");
    }
  });
  selectedSeats.clear();
  statusMessage.textContent = "";
  updateSummary();
};

const parseCsv = (value) =>
  value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);

const applyConfig = (event) => {
  event.preventDefault();
  const rows = parseCsv(rowsInput.value.toUpperCase());
  const premiumRows = parseCsv(premiumInput.value.toUpperCase());
  const occupied = parseCsv(occupiedInput.value.toUpperCase());
  const seatsPerRow = Number(seatsInput.value);
  const maxSelection = Number(maxInput.value);

  config = {
    ...config,
    rows: rows.length ? rows : defaultConfig.rows,
    premiumRows,
    seatsPerRow: Number.isNaN(seatsPerRow) ? defaultConfig.seatsPerRow : seatsPerRow,
    maxSelection: Number.isNaN(maxSelection) ? defaultConfig.maxSelection : maxSelection,
    occupiedSeats: occupied,
  };

  occupiedSeats = new Set(config.occupiedSeats);
  clearSelection();
  renderSeating();
  statusMessage.textContent = "Configuración actualizada.";
};

clearSelectionButton.addEventListener("click", clearSelection);
configForm.addEventListener("submit", applyConfig);

occupiedSeats = new Set(config.occupiedSeats);
renderSeating();
