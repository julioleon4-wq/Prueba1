const seatRows = [
  { row: "A", premium: false },
  { row: "B", premium: false },
  { row: "C", premium: true },
  { row: "D", premium: true },
  { row: "E", premium: true },
  { row: "F", premium: false },
  { row: "G", premium: false },
  { row: "H", premium: false },
];

const basePrice = 18;
const premiumPrice = 24;
const seatsPerRow = 12;
const aisleAfter = [4, 8];
const maxSelection = 6;
const occupiedSeats = new Set([
  "A3",
  "A4",
  "B6",
  "C2",
  "C9",
  "D5",
  "E7",
  "F1",
  "G10",
  "H8",
]);
const seatingContainer = document.getElementById("seating");
const selectedSeatsLabel = document.getElementById("selected-seats");
const selectedCountLabel = document.getElementById("selected-count");
const totalLabel = document.getElementById("total");
const reserveButton = document.getElementById("reserve");
const availableCountLabel = document.getElementById("available-count");
const clearSelectionButton = document.getElementById("clear-selection");
const statusMessage = document.getElementById("status-message");

const selectedSeats = new Map();

const createSeatButton = (seatId, isPremium, isOccupied) => {
  const button = document.createElement("button");
  button.className = "seat-button";
  button.type = "button";
  button.textContent = seatId.slice(1);
  button.dataset.seat = seatId;
  const price = isPremium ? premiumPrice : basePrice;
  button.setAttribute(
    "aria-label",
    `Asiento ${seatId} ${isPremium ? "premium" : "estándar"}: $${price.toFixed(2)}`
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
  seatRows.forEach(({ row, premium }) => {
    const rowWrapper = document.createElement("div");
    rowWrapper.className = "row";

    const leftLabel = document.createElement("span");
    leftLabel.className = "row-label";
    leftLabel.textContent = row;

    const rightLabel = document.createElement("span");
    rightLabel.className = "row-label";
    rightLabel.textContent = row;

    rowWrapper.appendChild(leftLabel);

    for (let seatNumber = 1; seatNumber <= seatsPerRow; seatNumber += 1) {
      const seatId = `${row}${seatNumber}`;
      const button = createSeatButton(seatId, premium, occupiedSeats.has(seatId));
      rowWrapper.appendChild(button);

      if (aisleAfter.includes(seatNumber)) {
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
    if (selectedSeats.size >= maxSelection) {
      statusMessage.textContent = `Solo puedes seleccionar hasta ${maxSelection} asientos.`;
      return;
    }
    selectedSeats.set(seatId, isPremium ? premiumPrice : basePrice);
    button.classList.add("selected");
    statusMessage.textContent = "";
  }

  updateSummary();
};

const updateSummary = () => {
  const selectedSeatList = Array.from(selectedSeats.keys()).sort();
  const count = selectedSeatList.length;
  const total = Array.from(selectedSeats.values()).reduce((sum, price) => sum + price, 0);

  selectedSeatsLabel.textContent = count ? selectedSeatList.join(", ") : "Ninguno";
  selectedCountLabel.textContent = count.toString();
  totalLabel.textContent = `$${total.toFixed(2)}`;
  reserveButton.disabled = count === 0;

  const totalAvailable = seatRows.length * seatsPerRow - occupiedSeats.size;
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

clearSelectionButton.addEventListener("click", clearSelection);

renderSeating();
