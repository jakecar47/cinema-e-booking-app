package com.example.ces.service;

import com.example.ces.dto.BookingRequestDTO;
import com.example.ces.dto.BookingRequestDTO.PaymentCardDTO;
import com.example.ces.model.*;
import com.example.ces.repository.BookingRepository;
import com.example.ces.repository.PaymentCardRepository;
import com.example.ces.repository.PromotionRepository;
import com.example.ces.repository.TicketRepository;
import com.example.ces.repository.UserRepository;
import com.example.ces.repository.ShowtimeRepository;
import com.example.ces.repository.MovieRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final ShowtimeRepository showtimeRepository;
    private final Random random = new Random();
    private final UserRepository userRepository;
    private final PaymentCardRepository paymentCardRepository;
    private final PromotionRepository promotionRepository;
    private final EmailService emailService;
    private final MovieRepository movieRepository;

    public BookingService(
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            ShowtimeRepository showtimeRepository,
            UserRepository userRepository,
            PaymentCardRepository paymentCardRepository,
            PromotionRepository promotionRepository,
            EmailService emailService,
            MovieRepository movieRepository
    ) {
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.showtimeRepository = showtimeRepository;
        this.userRepository = userRepository;
        this.paymentCardRepository = paymentCardRepository;
        this.promotionRepository = promotionRepository;
        this.emailService = emailService;
        this.movieRepository = movieRepository; 
    }

    // Create a booking (POST)
    public Booking createBooking(BookingRequestDTO request) {
        if (request == null)
            throw new IllegalArgumentException("BookingRequestDTO is required");

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + request.getUserId()));

        Optional<Showtime> showtimeOpt = showtimeRepository.findById(request.getShowtimeId());
        if (showtimeOpt.isEmpty()) {
            showtimeOpt = showtimeRepository.findAll().stream()
                    .filter(s -> s != null && request.getShowtimeId().equals(s.getShowtimeId()))
                    .findFirst();
        }
        Showtime showtime = showtimeOpt
                .orElseThrow(() -> new IllegalArgumentException("Showtime not found: " + request.getShowtimeId()));

        List<BookingRequestDTO.TicketSelectionDTO> ticketSelections = request.getTickets();
        if (ticketSelections == null || ticketSelections.isEmpty()) {
            throw new IllegalArgumentException("At least one ticket selection is required");
        }

        List<String> seatNumbers = new ArrayList<>();
        List<TicketType> ticketTypes = new ArrayList<>();
        List<Double> ticketPrices = new ArrayList<>();

        double basePrice = showtime.getBasePrice();

        // Determine ticket type
        for (BookingRequestDTO.TicketSelectionDTO sel : ticketSelections) {
            seatNumbers.add(sel.getSeatNumber());
            ticketTypes.add(sel.getTicketType());
            double multiplier = switch (sel.getTicketType()) {
                case ADULT -> 1.0;
                case SENIOR -> 0.8;
                case CHILD -> 0.7;
            };
            ticketPrices.add(basePrice * multiplier);
        }

        validateSeatsAvailable(showtime.getId(), seatNumbers);

        Booking booking = new Booking();
        booking.setBookingId(generateBookingId());
        booking.setUser(user);
        booking.setShowtime(showtime);
        booking.setBookingDate(LocalDate.now());
        booking.setNumberOfTickets(seatNumbers.size());
        booking.setStatus(BookingStatus.Confirmed);

        // Set payment card details
        if (request.getPaymentCard() != null) {
            PaymentCardDTO cardDTO = request.getPaymentCard();
            PaymentCard card = new PaymentCard();
            card.setCardNumber(cardDTO.getCardNumber());
            card.setCardType(cardDTO.getCardType());
            card.setExpiryDate(cardDTO.getExpiryDate());
            card.setBillingAddress(cardDTO.getBillingAddress());
            card.setCardholderName(cardDTO.getCardholderName());
            card.setUserId(user.getId());
            PaymentCard savedCard = paymentCardRepository.save(card);
            booking.setPaymentCard(savedCard);
        }

        if (request.getPromotionCode() != null && !request.getPromotionCode().isEmpty()) {
            promotionRepository.findByPromotionCodeIgnoreCase(request.getPromotionCode())
                    .ifPresent(booking::setPromotion);
        }

        Booking savedBooking = bookingRepository.save(booking);

        // Create ticket and set values
        List<Ticket> tickets = new ArrayList<>();
        for (int i = 0; i < seatNumbers.size(); i++) {
            Ticket ticket = new Ticket();
            ticket.setTicketId(generateTicketId());
            ticket.setSeatNumber(seatNumbers.get(i));
            ticket.setType(ticketTypes.get(i));
            ticket.setPrice(ticketPrices.get(i));
            ticket.setBookingId(savedBooking.getBookingId());

            try {
                String numericPart = showtime.getShowtimeId() != null
                        ? showtime.getShowtimeId().replaceAll("[^0-9]", "")
                        : "";
                if (!numericPart.isEmpty()) {
                    ticket.setShowtimeId(Integer.parseInt(numericPart));
                } else {
                    ticket.setShowtimeId(random.nextInt(999999));
                }
            } catch (Exception e) {
                ticket.setShowtimeId(random.nextInt(999999));
            }

            tickets.add(ticket);
        }

        List<Ticket> savedTickets = ticketRepository.saveAll(tickets);
        savedBooking.setTickets(savedTickets);

        double total = savedTickets.stream().mapToDouble(Ticket::getPrice).sum();

        if (savedBooking.getPromotion() != null && savedBooking.getPromotion().getDiscountPercent() > 0) {
            total -= total * (savedBooking.getPromotion().getDiscountPercent() / 100.0);
        }
        savedBooking.setTotalPrice(total);

        addSeatsToShowtimeTakenSeats(showtime.getId(), seatNumbers);

        Booking finalBooking = bookingRepository.save(savedBooking);

        // FIXED MOVIE TITLE LOOKUP (no getMovie())
        String movieTitle = "Movie";
        try {
            if (showtime.getMovieId() != null) {
                movieTitle = movieRepository.findById(showtime.getMovieId())
                        .map(Movie::getTitle)
                        .orElse("Movie");
            }
        } catch (Exception ex) {
            System.err.println("Could not fetch movie title for showtime " + showtime.getId());
        }

        // SEND EMAIL
        try {
            String userName = user.getName() != null ? user.getName() : user.getEmail();
            String showtimeInfo = showtime.getDate() + " at " + showtime.getTime();
            String seatsJoined = String.join(", ", seatNumbers);

            emailService.sendBookingConfirmationEmail(
                    user.getEmail(),
                    userName,
                    finalBooking.getBookingId(),
                    movieTitle,
                    showtimeInfo,
                    seatsJoined,
                    finalBooking.getTotalPrice());

            System.out.println("Sent booking confirmation email to " + user.getEmail());
        } catch (Exception e) {
            System.err.println("Failed to send booking confirmation email: " + e.getMessage());
        }

        return finalBooking;
    }

    /**
     * Delete a booking and remove its seat numbers from showtime's takenSeats
     */
    public void deleteBooking(String bookingId) {
        Optional<Booking> bookingOpt = bookingRepository.findById(bookingId);
        if (bookingOpt.isPresent()) {
            Booking booking = bookingOpt.get();

            // Get seat numbers from tickets before deletion
            List<String> seatNumbers = getSeatNumbersFromTickets(booking.getTickets());

            System.out.println("Deleting booking " + booking.getBookingId() + " and releasing seats: " + seatNumbers);

            // Delete all associated tickets from TICKETS TABLE
            for (Ticket ticket : booking.getTickets()) {
                ticketRepository.deleteById(ticket.getId());
            }

            // Remove seats from showtime's takenSeats - use MongoDB _id
            removeSeatsFromShowtimeTakenSeats(booking.getShowtime().getId(), seatNumbers);

            // Delete booking
            bookingRepository.deleteById(bookingId);

            System.out.println("Deleted booking and released seats from takenSeats: " + seatNumbers);
        }
    }

    /**
     * Update booking status and handle seat management for cancellations
     */
    public Booking updateBookingStatus(String bookingId, BookingStatus newStatus) {
        Optional<Booking> bookingOpt = bookingRepository.findById(bookingId);
        if (bookingOpt.isEmpty()) {
            throw new IllegalArgumentException("Booking not found: " + bookingId);
        }

        Booking booking = bookingOpt.get();
        BookingStatus oldStatus = booking.getStatus();

        if (oldStatus == newStatus) {
            return booking; // No change needed
        }

        List<String> seatNumbers = getSeatNumbersFromTickets(booking.getTickets());

        System.out.println("Updating booking " + booking.getBookingId() + " status from " +
                oldStatus + " to " + newStatus + " (seats: " + seatNumbers + ")");

        // Handle seat management based on status change - use MongoDB _id
        if (newStatus == BookingStatus.Cancelled && oldStatus != BookingStatus.Cancelled) {
            // Booking being cancelled - remove seats from takenSeats
            removeSeatsFromShowtimeTakenSeats(booking.getShowtime().getId(), seatNumbers);
            System.out.println("Released seats from takenSeats due to cancellation: " + seatNumbers);

        } else if (oldStatus == BookingStatus.Cancelled && newStatus != BookingStatus.Cancelled) {
            // Booking being reactivated - add seats back to takenSeats
            addSeatsToShowtimeTakenSeats(booking.getShowtime().getId(), seatNumbers);
            System.out.println("Added seats back to takenSeats due to reactivation: " + seatNumbers);
        }

        booking.setStatus(newStatus);
        return bookingRepository.save(booking);
    }

    // Method to retrieve all Bookings for a showtime
    public List<Booking> getBookingsByShowtimeId(String mongoShowtimeId) {
        // use repository query that looks up booking.showtime.id
        List<Booking> bookings = bookingRepository.findByShowtime_Id(mongoShowtimeId);
        syncShowtimeTakenSeatsWithBookings(mongoShowtimeId);
        return bookings;
    }

    /**
     * Sync all showtime takenSeats with actual booked tickets (repair function)
     * 
     * @param mongoShowtimeId MongoDB _id of the showtime
     */
    public void syncShowtimeTakenSeatsWithBookings(String mongoShowtimeId) {
        System.out.println("Syncing takenSeats for showtime " + mongoShowtimeId + " with actual bookings...");

        Optional<Showtime> showtimeOpt = showtimeRepository.findById(mongoShowtimeId);
        if (showtimeOpt.isEmpty()) {
            throw new IllegalArgumentException("Showtime not found: " + mongoShowtimeId);
        }

        Showtime showtime = showtimeOpt.get();

        // Get all confirmed bookings for this showtime using MongoDB _id
        List<Booking> confirmedBookings = bookingRepository.findAll().stream()
                .filter(booking -> booking.getShowtime().getId().equals(mongoShowtimeId))
                .filter(booking -> booking.getStatus() == BookingStatus.Confirmed ||
                        booking.getStatus() == BookingStatus.Pending)
                .toList();

        // Collect all seat numbers from confirmed bookings
        List<String> actualTakenSeats = new ArrayList<>();
        for (Booking booking : confirmedBookings) {
            actualTakenSeats.addAll(getSeatNumbersFromTickets(booking.getTickets()));
        }

        // Remove duplicates
        actualTakenSeats = actualTakenSeats.stream().distinct().collect(Collectors.toList());

        System.out.println("Found " + actualTakenSeats.size() + " taken seats from " +
                confirmedBookings.size() + " confirmed bookings");
        System.out.println("Actual taken seats: " + actualTakenSeats);

        // Update showtime with correct takenSeats
        showtime.setTakenSeats(actualTakenSeats);
        showtime.setSeatsBooked(actualTakenSeats.size());

        if (showtime.getSeats() != null) {
            showtime.setAvailableSeats(showtime.getSeats().size() - actualTakenSeats.size());
        }

        showtimeRepository.save(showtime);
        System.out.println("Synced showtime " + mongoShowtimeId + " takenSeats with actual bookings");
    }

    /**
     * Get booking statistics showing seat usage
     * 
     * @param mongoShowtimeId MongoDB _id of the showtime
     */
    public BookingStatistics getBookingStatistics(String mongoShowtimeId) {
        Optional<Showtime> showtimeOpt = showtimeRepository.findById(mongoShowtimeId);
        if (showtimeOpt.isEmpty()) {
            throw new IllegalArgumentException("Showtime not found: " + mongoShowtimeId);
        }

        Showtime showtime = showtimeOpt.get();

        // Get all bookings for this showtime using MongoDB _id
        List<Booking> allBookings = bookingRepository.findAll().stream()
                .filter(booking -> booking.getShowtime().getId().equals(mongoShowtimeId))
                .toList();

        // Count by status
        long confirmedBookings = allBookings.stream().filter(b -> b.getStatus() == BookingStatus.Confirmed).count();
        long pendingBookings = allBookings.stream().filter(b -> b.getStatus() == BookingStatus.Pending).count();
        long cancelledBookings = allBookings.stream().filter(b -> b.getStatus() == BookingStatus.Cancelled).count();

        // Count seats
        int totalSeats = showtime.getSeats() != null ? showtime.getSeats().size() : 0;
        int takenSeats = showtime.getTakenSeats() != null ? showtime.getTakenSeats().size() : 0;
        int availableSeats = totalSeats - takenSeats;

        return new BookingStatistics(
                mongoShowtimeId,
                allBookings.size(),
                confirmedBookings,
                pendingBookings,
                cancelledBookings,
                totalSeats,
                takenSeats,
                availableSeats,
                showtime.getTakenSeats());
    }

    // ============================
    // Private Helper Methods
    // ============================

    private void validateSeatsAvailable(String mongoShowtimeId, List<String> seatNumbers) {
        Optional<Showtime> showtimeOpt = showtimeRepository.findById(mongoShowtimeId);
        if (showtimeOpt.isEmpty()) {
            throw new IllegalArgumentException("Showtime not found: " + mongoShowtimeId);
        }

        Showtime showtime = showtimeOpt.get();

        // Check if showtime has seats initialized
        if (showtime.getSeats() == null || showtime.getSeats().isEmpty()) {
            throw new IllegalStateException("Showtime has no seats configured");
        }

        // Check if all requested seats exist
        for (String seatNumber : seatNumbers) {
            if (!showtime.getSeats().contains(seatNumber)) {
                throw new IllegalArgumentException("Seat " + seatNumber + " does not exist for this showtime");
            }
        }

        // Check if any seats are already taken
        if (showtime.getTakenSeats() != null) {
            for (String seatNumber : seatNumbers) {
                if (showtime.getTakenSeats().contains(seatNumber)) {
                    throw new IllegalStateException("Seat " + seatNumber + " is already taken");
                }
            }
        }
    }

    private void addSeatsToShowtimeTakenSeats(String mongoShowtimeId, List<String> seatNumbers) {
        Optional<Showtime> showtimeOpt = showtimeRepository.findById(mongoShowtimeId);
        if (showtimeOpt.isEmpty()) {
            return;
        }

        Showtime showtime = showtimeOpt.get();

        // Initialize takenSeats if null
        if (showtime.getTakenSeats() == null) {
            showtime.setTakenSeats(new ArrayList<>());
        }

        // Create a mutable copy and add new seats
        List<String> takenSeats = new ArrayList<>(showtime.getTakenSeats());

        for (String seatNumber : seatNumbers) {
            if (!takenSeats.contains(seatNumber)) {
                takenSeats.add(seatNumber);
            }
        }

        // Update showtime
        showtime.setTakenSeats(takenSeats);
        showtime.setSeatsBooked(takenSeats.size());

        if (showtime.getSeats() != null) {
            showtime.setAvailableSeats(showtime.getSeats().size() - takenSeats.size());
        }

        showtimeRepository.save(showtime);

        System.out.println("Added seats to takenSeats for showtime " + mongoShowtimeId + ": " + seatNumbers);
        System.out.println("Total takenSeats now: " + takenSeats.size());
    }

    private void removeSeatsFromShowtimeTakenSeats(String mongoShowtimeId, List<String> seatNumbers) {
        Optional<Showtime> showtimeOpt = showtimeRepository.findById(mongoShowtimeId);
        if (showtimeOpt.isEmpty()) {
            return;
        }

        Showtime showtime = showtimeOpt.get();

        if (showtime.getTakenSeats() == null) {
            showtime.setTakenSeats(new ArrayList<>());
        }

        // Create a mutable copy and remove seats
        List<String> takenSeats = new ArrayList<>(showtime.getTakenSeats());
        takenSeats.removeAll(seatNumbers);

        // Update showtime
        showtime.setTakenSeats(takenSeats);
        showtime.setSeatsBooked(takenSeats.size());

        if (showtime.getSeats() != null) {
            showtime.setAvailableSeats(showtime.getSeats().size() - takenSeats.size());
        }

        showtimeRepository.save(showtime);

        System.out.println("Removed seats from takenSeats for showtime " + mongoShowtimeId + ": " + seatNumbers);
        System.out.println("Total takenSeats now: " + takenSeats.size());
    }

    private List<String> getSeatNumbersFromTickets(List<Ticket> tickets) {
        if (tickets == null) {
            return new ArrayList<>();
        }
        return tickets.stream()
                .map(Ticket::getSeatNumber)
                .collect(Collectors.toList());
    }

    private int generateBookingId() {
        return 100000 + random.nextInt(900000);
    }

    private int generateTicketId() {
        return 1000 + random.nextInt(9000);
    }

    // Inner class for booking statistics
    public static class BookingStatistics {
        private final String showtimeId;
        private final int totalBookings;
        private final long confirmedBookings;
        private final long pendingBookings;
        private final long cancelledBookings;
        private final int totalSeats;
        private final int takenSeats;
        private final int availableSeats;
        private final List<String> takenSeatNumbers;

        public BookingStatistics(String showtimeId, int totalBookings, long confirmedBookings,
                long pendingBookings, long cancelledBookings, int totalSeats,
                int takenSeats, int availableSeats, List<String> takenSeatNumbers) {
            this.showtimeId = showtimeId;
            this.totalBookings = totalBookings;
            this.confirmedBookings = confirmedBookings;
            this.pendingBookings = pendingBookings;
            this.cancelledBookings = cancelledBookings;
            this.totalSeats = totalSeats;
            this.takenSeats = takenSeats;
            this.availableSeats = availableSeats;
            this.takenSeatNumbers = takenSeatNumbers;
        }

        // Getters
        public String getShowtimeId() {
            return showtimeId;
        }

        public int getTotalBookings() {
            return totalBookings;
        }

        public long getConfirmedBookings() {
            return confirmedBookings;
        }

        public long getPendingBookings() {
            return pendingBookings;
        }

        public long getCancelledBookings() {
            return cancelledBookings;
        }

        public int getTotalSeats() {
            return totalSeats;
        }

        public int getTakenSeats() {
            return takenSeats;
        }

        public int getAvailableSeats() {
            return availableSeats;
        }

        public List<String> getTakenSeatNumbers() {
            return takenSeatNumbers;
        }
    }

    public Booking getBookingById(String id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + id));
    }

    public List<Booking> getBookingsByUserId(String userId) {
        if (userId == null)
            return List.of();
        // use repository query that looks up booking.user.id
        return bookingRepository.findByUser_Id(userId);
    }
}