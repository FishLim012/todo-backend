package com.todo.todo_backend;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class TodoController {

    // 1. 确保 Firebase 初始化
    private synchronized void ensureFirebaseInitialized() {
        synchronized (FirebaseApp.class) {
            try {
                FirebaseApp.getInstance();
                return;
            } catch (IllegalStateException ignored) {
                // 默认实例不存在，继续初始化
            }

            try {
                File file = new File("src/main/resources/serviceAccountKey.json");
                if (!file.exists()) {
                    throw new IllegalStateException("找不到 Firebase 密钥文件: " + file.getAbsolutePath());
                }

                FileInputStream serviceAccount = new FileInputStream(file);
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();

                FirebaseApp.initializeApp(options);
                System.out.println("Firebase 默认实例初始化完成");
            } catch (Exception e) {
                throw new IllegalStateException("Firebase 初始化失败", e);
            }
        }
    }


    // ==================== 1. 获取特定用户的任务列表 ====================
    @GetMapping("/tasks")
    public List<Map<String, Object>> getTasks(@RequestParam(defaultValue = "TestUser") String username) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        try {
            ensureFirebaseInitialized();
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference docRef = db.collection("users").document(username);
            DocumentSnapshot document = docRef.get().get();

            if (document.exists() && document.contains("tasks")) {
                tasks = (List<Map<String, Object>>) document.get("tasks");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return tasks;
    }

    // 获取用户当前 5 圈连击进度 (0 ~ 4)
    @GetMapping("/progress")
    public Map<String, Object> getProgress(@RequestParam(defaultValue = "TestUser") String username) {
        Map<String, Object> res = new HashMap<>();
        int streak = 0;
        try {
            ensureFirebaseInitialized();
            Firestore db = FirestoreClient.getFirestore();
            DocumentSnapshot doc = db.collection("users").document(username).get().get();
            if (doc.exists() && doc.contains("streakCount")) {
                streak = doc.getLong("streakCount").intValue() % 5;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        res.put("streakCount", streak);
        return res;
    }

    // 切换任务状态（完成加分 + 5圈连击计数；撤销扣分）
    // 切换任务状态 或 点击任务旁边的 5 个小圈之一
    @PutMapping("/tasks/{id}")
    public Map<String, Object> updateTaskStatus(@PathVariable String id, @RequestBody Map<String, Object> request) {
        String username = request.get("username") != null ? request.get("username").toString().trim() : "TestUser";
        if (username.isEmpty()) username = "TestUser";
        String action = request.get("action") != null ? request.get("action").toString() : "TOGGLE";
        String newStatus = request.get("status") != null ? request.get("status").toString() : "COMPLETED";
        Map<String, Object> response = new HashMap<>();

        try {
            ensureFirebaseInitialized();
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference docRef = db.collection("users").document(username);

            DocumentSnapshot document = docRef.get().get();
            if (!document.exists()) {
                response.put("success", false);
                return response;
            }

            List<Map<String, Object>> tasks = document.contains("tasks") && document.get("tasks") != null
                    ? new ArrayList<>((List<Map<String, Object>>) document.get("tasks"))
                    : new ArrayList<>();
            int points = document.contains("points") && document.get("points") != null
                    ? document.getLong("points").intValue() : 0;
            String savedPassword = document.getString("password");

            int updatedDots = 0;

            for (Map<String, Object> task : tasks) {
                if (id.equals(task.get("id"))) {
                    String oldStatus = task.get("status") != null ? task.get("status").toString() : "PENDING";
                    int currentDots = task.get("dots") instanceof Number
                            ? ((Number) task.get("dots")).intValue() : 0;

                    if ("CIRCLE".equals(action)) {
                        // 点小圈：当前任务小圈 +1，每满5次变回全白(0)，并加10分
                        currentDots = (currentDots + 1) % 5;
                        task.put("dots", currentDots);
                        task.put("status", "COMPLETED");
                        points += 10;
                    } else {
                        // 点弯箭头：切换完成/撤销
                        task.put("status", newStatus);
                        if (!"COMPLETED".equals(oldStatus) && "COMPLETED".equals(newStatus)) {
                            points += 10;
                            currentDots = (currentDots + 1) % 5;
                            task.put("dots", currentDots);
                        } else if ("COMPLETED".equals(oldStatus) && !"COMPLETED".equals(newStatus)) {
                            points = Math.max(0, points - 10);
                        }
                    }
                    updatedDots = currentDots;
                    break;
                }
            }

            Map<String, Object> userData = new HashMap<>();
            userData.put("username", username);
            if (savedPassword != null) userData.put("password", savedPassword);
            userData.put("points", points);
            userData.put("tasks", tasks);
            docRef.set(userData).get();

            response.put("success", true);
            response.put("points", points);
            response.put("dots", updatedDots);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
        }
        return response;
    }

    // ==================== 2. 添加任务 ====================
    @PostMapping("/tasks")
    public Map<String, Object> addTask(@RequestBody Map<String, Object> request) {
        String username = request.get("username") != null ? request.get("username").toString().trim() : "TestUser";
        if (username.isEmpty()) username = "TestUser";
        String taskName = request.get("taskName") != null ? request.get("taskName").toString().trim() : "";
        Map<String, Object> response = new HashMap<>();

        if (taskName.isEmpty()) {
            response.put("success", false);
            response.put("message", "任务内容不能为空");
            return response;
        }

        try {
            ensureFirebaseInitialized();
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference docRef = db.collection("users").document(username);

            DocumentSnapshot document = docRef.get().get();
            List<Map<String, Object>> tasks = new ArrayList<>();
            int currentPoints = 0;
            int streakCount = 0;
            String savedPassword = null;

            if (document.exists()) {
                if (document.contains("tasks") && document.get("tasks") != null) {
                    tasks = new ArrayList<>((List<Map<String, Object>>) document.get("tasks"));
                }
                if (document.contains("points") && document.get("points") != null) {
                    currentPoints = document.getLong("points").intValue();
                }
                if (document.contains("streakCount") && document.get("streakCount") != null) {
                    streakCount = document.getLong("streakCount").intValue();
                }
                savedPassword = document.getString("password");
            }

            String taskId = UUID.randomUUID().toString();
            Map<String, Object> newTask = new HashMap<>();
            newTask.put("id", taskId);
            newTask.put("taskName", taskName);
            newTask.put("status", "PENDING");

            tasks.add(newTask);

            Map<String, Object> userData = new HashMap<>();
            userData.put("username", username);
            if (savedPassword != null) userData.put("password", savedPassword);
            userData.put("points", currentPoints);
            userData.put("streakCount", streakCount);
            userData.put("tasks", tasks);
            docRef.set(userData).get();

            response.put("success", true);
            response.put("task", newTask);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", e.getMessage());
        }
        return response;
    }

    // ==================== 4. 删除任务 ====================
    @DeleteMapping("/tasks/{id}")
    public Map<String, Object> deleteTask(@RequestParam(defaultValue = "TestUser") String username,
                                          @PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        try {
            ensureFirebaseInitialized();
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference docRef = db.collection("users").document(username);

            DocumentSnapshot document = docRef.get().get();
            if (!document.exists()) {
                response.put("success", false);
                return response;
            }

            List<Map<String, Object>> tasks = document.contains("tasks") && document.get("tasks") != null
                    ? new ArrayList<>((List<Map<String, Object>>) document.get("tasks"))
                    : new ArrayList<>();
            int points = document.contains("points") && document.get("points") != null
                    ? document.getLong("points").intValue() : 0;
            int streakCount = document.contains("streakCount") && document.get("streakCount") != null
                    ? document.getLong("streakCount").intValue() : 0;
            String savedPassword = document.getString("password");

            tasks.removeIf(task -> id.equals(task.get("id")));

            Map<String, Object> userData = new HashMap<>();
            userData.put("username", username);
            if (savedPassword != null) userData.put("password", savedPassword);
            userData.put("points", points);
            userData.put("streakCount", streakCount);
            userData.put("tasks", tasks);
            docRef.set(userData).get();

            response.put("success", true);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
        }
        return response;
    }

    

    // ==================== 5. 获取全局排行榜 ====================
    @GetMapping("/leaderboard")
    public List<Map<String, Object>> getLeaderboard() {
        List<Map<String, Object>> leaderboard = new ArrayList<>();
        try {
            ensureFirebaseInitialized();
            Firestore db = FirestoreClient.getFirestore();
            ApiFuture<QuerySnapshot> future = db.collection("users").get();
            List<QueryDocumentSnapshot> documents = future.get().getDocuments();

            for (QueryDocumentSnapshot doc : documents) {
                String username = doc.getString("username");
                Long points = doc.getLong("points");
                if (username != null) {
                    Map<String, Object> userEntry = new HashMap<>();
                    userEntry.put("username", username);
                    userEntry.put("points", points != null ? points : 0);
                    leaderboard.add(userEntry);
                }
            }

            // 后端算法：积分降序排序
            leaderboard.sort((u1, u2) -> {
                Number p1 = (Number) u1.getOrDefault("points", 0);
                Number p2 = (Number) u2.getOrDefault("points", 0);
                return Integer.compare(p2.intValue(), p1.intValue());
            });
            
            // 取前 10 名
            int limit = Math.min(leaderboard.size(), 10);
            leaderboard = leaderboard.subList(0, limit);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return leaderboard;
    }

    // ==================== 0. 账号注册 ====================
    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody Map<String, String> request) {
        String username = request.get("username") != null ? request.get("username").trim() : "";
        String password = request.get("password") != null ? request.get("password") : "";
        Map<String, Object> response = new HashMap<>();

        if (username.isEmpty() || password.isEmpty()) {
            response.put("success", false);
            response.put("message", "用户名和密码不能为空");
            return response;
        }

        try {
            ensureFirebaseInitialized();
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference docRef = db.collection("users").document(username);
            DocumentSnapshot doc = docRef.get().get();

            if (doc.exists()) {
                response.put("success", false);
                response.put("message", "该用户名已被注册，请换一个或直接登录");
                return response;
            }

            Map<String, Object> userData = new HashMap<>();
            userData.put("username", username);
            userData.put("password", password);
            userData.put("points", 0);
            userData.put("tasks", new ArrayList<>());

            docRef.set(userData).get();

            response.put("success", true);
            response.put("username", username);
            response.put("message", "注册成功！已自动登录");
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "注册异常: " + e.getMessage());
        }
        return response;
    }

    // ==================== 0.1 账号登录 ====================
    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, String> request) {
        String username = request.get("username") != null ? request.get("username").trim() : "";
        String password = request.get("password") != null ? request.get("password") : "";
        Map<String, Object> response = new HashMap<>();

        if (username.isEmpty() || password.isEmpty()) {
            response.put("success", false);
            response.put("message", "请输入用户名和密码");
            return response;
        }

        try {
            ensureFirebaseInitialized();
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference docRef = db.collection("users").document(username);
            DocumentSnapshot doc = docRef.get().get();

            if (!doc.exists()) {
                response.put("success", false);
                response.put("message", "用户不存在，请先注册");
                return response;
            }

            String savedPassword = doc.getString("password");
            if (savedPassword == null || !savedPassword.equals(password)) {
                response.put("success", false);
                response.put("message", "密码错误");
                return response;
            }

            response.put("success", true);
            response.put("username", username);
            response.put("message", "登录成功");
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "登录异常: " + e.getMessage());
        }
        return response;
    }
}