# Cinema E-Booking Software

A full-stack movie ticket booking web application built using **React.js**, **Spring Boot**, **Java**, and **MongoDB Atlas**.  
The application allows users to browse movies, view showtimes, select seats, and book tickets through a seamless client-server architecture.

---

## Tech Stack

### Frontend
- React.js
- JavaScript (ES6+)
- HTML5 / CSS3

### Backend
- Java
- Spring Boot
- Maven
- RESTful APIs

### Database
- MongoDB Atlas (Cloud Database)

### Version Control
- Git & GitHub

---

## Features

- Browse available movies
- View showtimes
- Interactive seat selection
- Ticket booking workflow
- RESTful API communication
- Cloud-based MongoDB data storage
- Modular backend architecture (Controller -> Service -> Repository pattern)

---

## System Architecture

The project follows a client-server architecture:

- **React Frontend** handles UI rendering and user interactions.
- **Spring Boot Backend** processes business logic and exposes RESTful APIs.
- **MongoDB Atlas** stores movies, showtimes, users, and booking data.
- Frontend and backend communicate via HTTP requests using REST APIs.

---

## How To Run

git clone https://github.com/jakecar47/cinema-e-booking-app.git
cd cinema-e-booking-app

Frontend setup (runs on http://localhost:3000):
cd cinema-e-booking-app
npm install
npm run dev

Backend setup (runs on http://localhost:8080):
cd backend
mvn spring-boot:run
