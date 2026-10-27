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

    // ==================== 2. 添加任务 ====================
    @PostMapping("/tasks")
    public Map<String, Object> addTask(@RequestBody Map<String, Object> request) {
        String username = (String) request.getOrDefault("username", "TestUser");
        String taskName = (String) request.get("taskName");
        Map<String, Object> response = new HashMap<>();

        try {
            ensureFirebaseInitialized();
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference docRef = db.collection("users").document(username);

            DocumentSnapshot document = docRef.get().get();
            List<Map<String, Object>> tasks = new ArrayList<>();
            int currentPoints = 0;

            if (document.exists()) {
                if (document.contains("tasks")) {
                    tasks = (List<Map<String, Object>>) document.get("tasks");
                }
                if (document.contains("points")) {
                    currentPoints = document.getLong("points").intValue();
                }
            }

            // 生成唯一任务 ID
            String taskId = UUID.randomUUID().toString();
            Map<String, Object> newTask = new HashMap<>();
            newTask.put("id", taskId);
            newTask.put("taskName", taskName);
            newTask.put("status", "PENDING");

            tasks.add(newTask);

            // 写回云端
            Map<String, Object> userData = new HashMap<>();
            userData.put("username", username);
            userData.put("points", currentPoints);
            userData.put("tasks", tasks);
            docRef.set(userData).get();

            response.put("success", true);
            response.put("task", newTask);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
        }
        return response;
    }

    // ==================== 3. 切换任务状态（完成加分 / 撤销扣分） ====================
    @PutMapping("/tasks/{id}")
    public Map<String, Object> updateTaskStatus(@PathVariable String id, @RequestBody Map<String, Object> request) {
        String username = (String) request.getOrDefault("username", "TestUser");
        String newStatus = (String) request.get("status"); // "COMPLETED" 或 "PENDING"
        Map<String, Object> response = new HashMap<>();

        try {
            ensureFirebaseInitialized();
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference docRef = db.collection("users").document(username);

            DocumentSnapshot document = docRef.get().get();
            if (!document.exists()) return response;

            List<Map<String, Object>> tasks = (List<Map<String, Object>>) document.get("tasks");
            int points = document.contains("points") ? document.getLong("points").intValue() : 0;

            if (tasks != null) {
                for (Map<String, Object> task : tasks) {
                    if (task.get("id").equals(id)) {
                        String oldStatus = (String) task.get("status");
                        task.put("status", newStatus);

                        // 状态从 PENDING 变成 COMPLETED -> 积分 +10
                        if (!"COMPLETED".equals(oldStatus) && "COMPLETED".equals(newStatus)) {
                            points += 10;
                        } 
                        // 状态从 COMPLETED 变成 PENDING -> 积分 -10（可选项，防止撤销不扣分）
                        else if ("COMPLETED".equals(oldStatus) && !"COMPLETED".equals(newStatus)) {
                            points = Math.max(0, points - 10);
                        }
                        break;
                    }
                }
            }

            Map<String, Object> userData = new HashMap<>();
            userData.put("username", username);
            userData.put("points", points);
            userData.put("tasks", tasks);
            docRef.set(userData).get();

            response.put("success", true);
            response.put("points", points);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
        }
        return response;
    }

    // ==================== 4. 删除任务 ====================
    @DeleteMapping("/tasks/{id}")
    public Map<String, Object> deleteTask(@RequestParam String username, @PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        try {
            ensureFirebaseInitialized();
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference docRef = db.collection("users").document(username);

            DocumentSnapshot document = docRef.get().get();
            if (!document.exists()) return response;

            List<Map<String, Object>> tasks = (List<Map<String, Object>>) document.get("tasks");
            int points = document.contains("points") ? document.getLong("points").intValue() : 0;

            if (tasks != null) {
                tasks.removeIf(task -> task.get("id").equals(id));
            }

            Map<String, Object> userData = new HashMap<>();
            userData.put("username", username);
            userData.put("points", points);
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
}