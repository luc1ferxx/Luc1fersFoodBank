# OnlineOrder

Monorepo structure:

- `backend/` Spring Boot backend
- `frontend/` React frontend

Typical local commands:

- Start PostgreSQL first: `cd backend && docker compose up -d`
- Backend: `cd backend && gradlew.bat bootRun`
- Frontend: `cd frontend && npm start`

Backend notes:

- PostgreSQL is the default datasource on `localhost:5433`, database `onlineorder`
- H2 is still available as a fallback with `SPRING_PROFILES_ACTIVE=h2`
