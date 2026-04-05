# Run Demo

## IntelliJ Backend Demo

1. Open `backEnd` as a Gradle project in IntelliJ.
2. Use JDK 17 or newer for the project SDK.
3. Run `com.laioffer.onlineorder.OnlineOrderApplication`.
4. Open `http://localhost:8080`.

The backend now defaults to an embedded H2 database, so you do not need PostgreSQL just to run the demo locally.

Demo account:

- Email: `demo@laifood.com`
- Password: `demo123`

## If You Change The React Frontend

The Spring app serves the built files from `backEnd/src/main/resources/public`.

When you update files under `doordash-app`, rebuild and sync them back into the backend:

```bash
cd doordash-app
npm install
npm run deploy:backend
```

Then rerun the Spring Boot app from IntelliJ and refresh `http://localhost:8080`.

## Optional Frontend Dev Server

If you want hot reload while editing React:

```bash
cd doordash-app
npm install
npm start
```

Then open `http://localhost:3000`.
