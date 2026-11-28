# Cloud Todo & Leaderboard 📝

A full-stack to-do list web app built with Spring Boot and Firebase Firestore, with user login/register, per-user cloud tasks, points, and a global leaderboard.

## What it does
This app lets users manage their own to-do tasks in the cloud and earn points to compete on a shared leaderboard. After opening the web page, you can log in or register an account from the top-left bar (or use the guest account), switch between Task List and Completed, and track your progress.

| Feature | What you can do |
| --- | --- |
| Account & Login |	Register a new username and password, log in, or log out from the top-left bar |
| Task List |	Add tasks, click the 5 small circles next to a task to add points (resets back to white after 5 clicks), click the check button to finish and move a task to Completed, or click the delete button |
| Completed Tab |	View finished tasks, move a task back to Task List with the undo button, or delete it |
| Global Leaderboard	| View top users sorted by points from highest to lowest |

All user accounts, tasks, and points are stored in Firebase Firestore, so different accounts keep their own separate task lists while sharing the global leaderboard.

## Try it out
Open the app in your browser at http://localhost:8080 (or http://<your-computer-ip>:8080 on a phone/device on the same Wi-Fi).

Click Login / Register in the top-left corner to register a new account or log in with an existing one.

Add a task and click the circles or check icon to see your points update on the leaderboard.

## Project structure

```text
todo-backend/
├── pom.xml                                   # Maven project configuration and dependencies
├── README.md                                 # Project documentation
└── src/
    └── main/
        ├── java/
        │   └── com/todo/todo_backend/
        │       ├── TodoBackendApplication.java   # Spring Boot main entry point
        │       ├── TodoController.java         # REST APIs for auth, tasks, and leaderboard
        │       └── model/
        │           └── User.java               # User model class
        └── resources/
            ├── serviceAccountKey.json.example  # Example placeholder (do not commit real key)
            └── static/
                └── index.html                  # Frontend UI (task list, tabs, auth modal, leaderboard)
```

## Data model at a glance
Each user is stored in the users collection in Firebase Firestore by username.

A user document stores username, password, points, and a tasks list.

Each item in tasks has an id, taskName, status (PENDING or COMPLETED), and dots (0 to 4, wrapping back to 0 after 5).

GET /api/leaderboard reads users from Firestore, sorts them by points in descending order, and returns the top 10.

## Running
Put your Firebase service account JSON file in src/main/resources/serviceAccountKey.json (make sure the real key is ignored by .gitignore).

Start the Spring Boot backend with Maven:
```text
./mvnw spring-boot:run
```
Open http://localhost:8080 in your browser.

## Known limitations
Plaintext passwords — passwords are stored directly in Firestore for simplicity in a student/demo project; not meant for production.

Local service key path — TodoController loads src/main/resources/serviceAccountKey.json from the project path when running locally.

Basic error handling — some network or Firestore errors only log to the console or return simple status messages.
