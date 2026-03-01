package com.example.ces.service;

import com.example.ces.dto.BookingRequestDTO;
import com.example.ces.model.*;
import com.example.ces.repository.BookingRepository;
import com.example.ces.repository.UserRepository;
import com.example.ces.repository.ShowtimeRepository;
import com.example.ces.repository.TicketRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@Component
public class BookingDataLoader implements CommandLineRunner {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ShowtimeRepository showtimeRepository;
    private final TicketRepository ticketRepository;
    private final BookingService bookingService;
    private final Random random = new Random();

    public BookingDataLoader(BookingRepository bookingRepository,
            UserRepository userRepository,
            ShowtimeRepository showtimeRepository,
            TicketRepository ticketRepository,
            BookingService bookingService) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.showtimeRepository = showtimeRepository;
        this.ticketRepository = ticketRepository;
        this.bookingService = bookingService;
    }

    @Override
    public void run(String... args) throws Exception {
        // Clear any existing data if starting fresh
        if (bookingRepository.count() == 0 && ticketRepository.count() > 0) {
            System.out.println("Found orphaned tickets - clearing tickets table...");
            ticketRepository.deleteAll();
            System.out.println("Tickets table cleared - will be populated through bookings only");
        }

        // Clear taken seats for all showtimes (dev-only convenience)
        // clearAllShowtimeTakenSeats();

        // Ensure existing showtimes have the 10x10 seat layout
        updateExistingShowtimesWithSeats();

        // Only load bookings (and their tickets) if booking table is empty
        if (bookingRepository.count() == 0) {
            loadDummyBookingsWithTickets();
        }

        // Verify the tickets table is populated correctly
        verifyTicketsTable();
        syncShowtimeTakenSeatsWithBookings();
        syncEmbeddedShowtimesInBookings();
        assignPaymentCardsAndPromotionsToBookings();
    }

    private List<PaymentCard> createSamplePaymentCards(List<User> users) {
        List<PaymentCard> cards = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            PaymentCard card = new PaymentCard();
            card.setCardNumber("41111111111111" + (10 + i));
            card.setCardType("VISA");
            card.setExpiryDate("12/28");
            card.setCardholderName(users.get(i).getFirstName() + " " + users.get(i).getLastName());
            card.setUserId(users.get(i).getId());
            card.setBillingAddress("123 Main St, City, State");
            cards.add(card);
        }
        return cards;
    }

    private List<Promotion> createSamplePromotions() {
        List<Promotion> promos = new ArrayList<>();
        Promotion promo1 = new Promotion();
        promo1.setPromotionCode("WELCOME10");
        promo1.setDiscountPercent(10);
        promos.add(promo1);

        Promotion promo2 = new Promotion();
        promo2.setPromotionCode("FAMILY20");
        promo2.setDiscountPercent(20);
        promos.add(promo2);

        return promos;
    }

    private void assignPaymentCardsAndPromotionsToBookings() {
        List<Booking> allBookings = bookingRepository.findAll();
        List<User> users = userRepository.findAll();
        List<PaymentCard> cards = createSamplePaymentCards(users);
        List<Promotion> promos = createSamplePromotions();

        for (int i = 0; i < allBookings.size(); i++) {
            Booking booking = allBookings.get(i);
            // Assign a card based on user index
            int userIdx = users.indexOf(booking.getUser());
            if (userIdx >= 0 && userIdx < cards.size()) {
                booking.setPaymentCard(cards.get(userIdx));
            } else {
                booking.setPaymentCard(cards.get(0));
            }
            // Assign a promotion alternately
            Promotion promo = promos.get(i % promos.size());
            booking.setPromotion(promo);

            // Apply promotion discount to total price if a promotion is used
            double originalTotal = 0.0;
            if (booking.getTickets() != null) {
                for (Ticket t : booking.getTickets()) {
                    originalTotal += t.getPrice();
                }
            }
            if (promo != null && promo.getDiscountPercent() > 0) {
                double discount = originalTotal * (promo.getDiscountPercent() / 100.0);
                booking.setTotalPrice(originalTotal - discount);
            } else {
                booking.setTotalPrice(originalTotal);
            }
        }
        bookingRepository.saveAll(allBookings);
    }

    private void clearAllShowtimeTakenSeats() {
        List<Showtime> allShowtimes = showtimeRepository.findAll();
        if (allShowtimes == null || allShowtimes.isEmpty()) {
            System.out.println("No showtimes to clear takenSeats for.");
            return;
        }

        System.out.println("Clearing takenSeats for " + allShowtimes.size() + " showtimes...");
        for (Showtime s : allShowtimes) {
            boolean hadTaken = s.getTakenSeats() != null && !s.getTakenSeats().isEmpty();
            if (hadTaken) {
                s.setTakenSeats(new ArrayList<>());
                s.setSeatsBooked(0);
                int totalSeats = (s.getSeats() == null) ? 0 : s.getSeats().size();
                s.setAvailableSeats(totalSeats);
            } else if (s.getSeats() != null && s.getAvailableSeats() == 0 && s.getSeatsBooked() == 0) {
                s.setAvailableSeats(s.getSeats().size());
            }
        }

        showtimeRepository.saveAll(allShowtimes);
        System.out.println("Cleared takenSeats for all showtimes.");
    }

    private Showtime findShowtimeByMongoId(String mongoId) {
        if (mongoId == null)
            return null;

        return showtimeRepository.findById(mongoId)
                .orElse(null);
    }

    private void loadDummyBookingsWithTickets() {
        System.out.println("\n Loading dummy bookings - each will create tickets in the tickets table...");

        List<User> users = createDummyUsers();
        List<Showtime> existingShowtimes = showtimeRepository.findAll();

        if (existingShowtimes.isEmpty()) {
            System.err.println("No showtimes found in database. Cannot create bookings.");
            return;
        }

        System.out.println("Found " + existingShowtimes.size() + " existing showtimes");

        int showtimeIndex = 0;

        // Use actual showtimes from database - no hardcoded IDs
        Showtime showtime1 = existingShowtimes.get(showtimeIndex % existingShowtimes.size());
        createBookingAndPopulateTickets(users.get(0), showtime1,
                Arrays.asList("A3", "A4"),
                Arrays.asList(TicketType.ADULT, TicketType.ADULT),
                BookingStatus.Confirmed, "Friends outing");
        showtimeIndex++;

        Showtime showtime2 = existingShowtimes.get(showtimeIndex % existingShowtimes.size());
        createBookingAndPopulateTickets(users.get(1), showtime2,
                Arrays.asList("B1"),
                Arrays.asList(TicketType.ADULT),
                BookingStatus.Confirmed, "Solo booking");
        showtimeIndex++;

        Showtime showtime3 = existingShowtimes.get(showtimeIndex % existingShowtimes.size());
        createBookingAndPopulateTickets(users.get(1), showtime3,
                Arrays.asList("G7"),
                Arrays.asList(TicketType.ADULT),
                BookingStatus.Confirmed, "Solo booking");
        showtimeIndex++;

        Showtime showtime4 = existingShowtimes.get(showtimeIndex % existingShowtimes.size());
        createBookingAndPopulateTickets(users.get(0), showtime4,
                Arrays.asList("A1", "A2"),
                Arrays.asList(TicketType.ADULT, TicketType.CHILD),
                BookingStatus.Confirmed, "Family movie night");
        showtimeIndex++;

        Showtime showtime5 = existingShowtimes.get(showtimeIndex % existingShowtimes.size());
        createBookingAndPopulateTickets(users.get(1), showtime5,
                Arrays.asList("B5"),
                Arrays.asList(TicketType.ADULT),
                BookingStatus.Confirmed, "Solo movie");
        showtimeIndex++;

        Showtime showtime6 = existingShowtimes.get(showtimeIndex % existingShowtimes.size());
        createBookingAndPopulateTickets(users.get(2), showtime6,
                Arrays.asList("C1", "C2", "C3"),
                Arrays.asList(TicketType.ADULT, TicketType.SENIOR, TicketType.CHILD),
                BookingStatus.Pending, "Group outing");
        showtimeIndex++;

        Showtime showtime7 = existingShowtimes.get(showtimeIndex % existingShowtimes.size());
        createBookingAndPopulateTickets(users.get(0), showtime7,
                Arrays.asList("D4", "D5"),
                Arrays.asList(TicketType.ADULT, TicketType.ADULT),
                BookingStatus.Confirmed, "Date night");
        showtimeIndex++;

        Showtime showtime8 = existingShowtimes.get(showtimeIndex % existingShowtimes.size());
        createBookingAndPopulateTickets(users.get(1), showtime8,
                Arrays.asList("F1", "F2", "G8", "H10"),
                Arrays.asList(TicketType.ADULT, TicketType.ADULT, TicketType.CHILD, TicketType.CHILD),
                BookingStatus.Cancelled, "Group booking - cancelled");
        showtimeIndex++;

        Showtime showtime9 = existingShowtimes.get(showtimeIndex % existingShowtimes.size());
        createBookingAndPopulateTickets(users.get(2), showtime9,
                Arrays.asList("J1", "J10", "E5"),
                Arrays.asList(TicketType.SENIOR, TicketType.ADULT, TicketType.CHILD),
                BookingStatus.Confirmed, "Mixed seating");

        // Manual Dark Knight 1:30 PM bookings
        System.out.println("\n--- MANUAL DARK KNIGHT 1:30 PM BOOKINGS ---");
        Showtime darkKnightShowtime = findShowtimeByMongoId("693131f53bb59f0bf735c6c0");

        if (darkKnightShowtime != null) {
            createBookingAndPopulateTickets(users.get(0), darkKnightShowtime,
                    Arrays.asList("A5", "A6"),
                    Arrays.asList(TicketType.ADULT, TicketType.ADULT),
                    BookingStatus.Confirmed, "Dark Knight - 1:30 PM booking 1");

            createBookingAndPopulateTickets(users.get(1), darkKnightShowtime,
                    Arrays.asList("B3", "B4", "B5"),
                    Arrays.asList(TicketType.ADULT, TicketType.CHILD, TicketType.CHILD),
                    BookingStatus.Confirmed, "Dark Knight - 1:30 PM booking 2");

            createBookingAndPopulateTickets(users.get(2), darkKnightShowtime,
                    Arrays.asList("C8"),
                    Arrays.asList(TicketType.SENIOR),
                    BookingStatus.Confirmed, "Dark Knight - 1:30 PM booking 3");
        } else {
            System.out.println("Dark Knight 1:30 PM showtime not found - skipping manual bookings");
        }
    }

    // Create new booking and populate the tickets
    private void createBookingAndPopulateTickets(User user, Showtime showtime,
            List<String> seatNumbers,
            List<TicketType> ticketTypes,
            BookingStatus status, String description) {

        System.out.println("\n--- Creating booking: " + description + " ---");
        System.out.println("This will add " + seatNumbers.size() + " tickets to the tickets table");

        // Build BookingRequestDTO to call new
        // BookingService.createBooking(BookingRequestDTO)
        BookingRequestDTO request = new BookingRequestDTO();
        request.setShowtimeId(showtime.getId()); // use MongoDB _id for showtime
        request.setUserId(user.getId());

        List<BookingRequestDTO.TicketSelectionDTO> selections = new ArrayList<>();
        for (int i = 0; i < seatNumbers.size(); i++) {
            BookingRequestDTO.TicketSelectionDTO sel = new BookingRequestDTO.TicketSelectionDTO();
            sel.setSeatNumber(seatNumbers.get(i));
            sel.setTicketType(ticketTypes.get(i));
            selections.add(sel);
        }
        request.setTickets(selections);

        try {
            Booking savedBooking = bookingService.createBooking(request);

            // Update booking status if needed (bookingService.updateBookingStatus expects
            // booking.mongo id)
            if (savedBooking.getStatus() != status) {
                bookingService.updateBookingStatus(savedBooking.getId(), status);
            }

            System.out.println("Booking " + savedBooking.getBookingId() + " created");
            System.out.println("   Added " + savedBooking.getTickets().size() + " tickets to tickets table");

        } catch (Exception e) {
            System.err.println("Error creating booking: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Update all existing showtimes with new booking
    public void syncShowtimeTakenSeatsWithBookings() {
        List<Showtime> allShowtimes = showtimeRepository.findAll();
        for (Showtime showtime : allShowtimes) {
            List<Booking> bookingsForShowtime = bookingRepository.findByShowtime_Id(showtime.getId());
            List<String> takenSeats = new ArrayList<>();
            for (Booking booking : bookingsForShowtime) {
                for (Ticket ticket : booking.getTickets()) {
                    takenSeats.add(ticket.getSeatNumber());
                }
            }
            showtime.setTakenSeats(takenSeats);
            showtime.setSeatsBooked(takenSeats.size());
            int totalSeats = (showtime.getSeats() == null) ? 0 : showtime.getSeats().size();
            showtime.setAvailableSeats(totalSeats - takenSeats.size());
        }
        showtimeRepository.saveAll(allShowtimes);
    }

    public void syncEmbeddedShowtimesInBookings() {
        List<Booking> allBookings = bookingRepository.findAll();
        for (Booking booking : allBookings) {
            Showtime latestShowtime = showtimeRepository.findById(booking.getShowtime().getId()).orElse(null);
            if (latestShowtime != null) {
                booking.setShowtime(latestShowtime);
            }
        }
        bookingRepository.saveAll(allBookings);
    }

    // FOR TESTING, verifying ticket table is populated
    private void verifyTicketsTable() {
        System.out.println("\n=== TICKETS TABLE VERIFICATION ===");

        List<Ticket> allTickets = ticketRepository.findAll();
        List<Booking> allBookings = bookingRepository.findAll();

        System.out.println("Tickets in tickets table: " + allTickets.size());
        System.out.println("Bookings in bookings table: " + allBookings.size());

        int ticketsFromBookings = allBookings.stream()
                .mapToInt(booking -> booking.getTickets() != null ? booking.getTickets().size() : 0)
                .sum();

        System.out.println("Tickets referenced by bookings: " + ticketsFromBookings);

        int linkedTickets = 0;
        int orphanedTickets = 0;

        for (Ticket ticket : allTickets) {
            boolean foundInBooking = allBookings.stream()
                    .anyMatch(booking -> booking.getBookingId() == ticket.getBookingId());

            if (foundInBooking) {
                linkedTickets++;
            } else {
                orphanedTickets++;
            }
        }

        System.out.println("Properly linked tickets: " + linkedTickets);
        System.out.println(" Orphaned tickets: " + orphanedTickets);

        if (orphanedTickets == 0 && allTickets.size() == ticketsFromBookings) {
            System.out.println("PERFECT: All tickets in tickets table are from bookings!");
        }

        System.out.println("=====================================\n");
    }

    // To replace old seat layout
    private void updateExistingShowtimesWithSeats() {
        System.out.println("Updating existing showtimes with 10x10 seat layout...");

        List<Showtime> allShowtimes = showtimeRepository.findAll();
        boolean updated = false;

        for (Showtime showtime : allShowtimes) {
            if (showtime.getSeats() == null || showtime.getSeats().isEmpty()) {
                List<String> seats = create10x10SeatLayout();
                showtime.setSeats(seats);

                if (showtime.getTakenSeats() == null) {
                    showtime.setTakenSeats(new ArrayList<>());
                }

                showtime.setAvailableSeats(100 - showtime.getTakenSeats().size());
                showtime.setSeatsBooked(showtime.getTakenSeats().size());

                updated = true;
            }
        }

        if (updated) {
            showtimeRepository.saveAll(allShowtimes);
        }
    }
    
    // Utilize createUser
    private List<User> createDummyUsers() {
        if (userRepository.count() == 0) {
            List<User> users = Arrays.asList(
                    createUser("john.doe@email.com", "John", "Doe"),
                    createUser("jane.smith@email.com", "Jane", "Smith"),
                    createUser("bob.wilson@email.com", "Bob", "Wilson"));
            return (List<User>) userRepository.saveAll(users);
        } else {
            return userRepository.findAll();
        }
    }

    // Method to create a new user
    private User createUser(String email, String firstName, String lastName) {
        User user = new User();
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPassword("hashedPassword123");
        user.setPhone("555-0123");
        user.setIsActive(true);
        return user;
    }

    // Create 10x10 seat array
    private List<String> create10x10SeatLayout() {
        List<String> seats = new ArrayList<>();
        for (char row = 'A'; row <= 'J'; row++) {
            for (int seatNum = 1; seatNum <= 10; seatNum++) {
                seats.add(row + String.valueOf(seatNum));
            }
        }
        return seats;
    }
}