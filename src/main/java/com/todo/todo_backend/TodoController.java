package com.todo.todo_backend;

import org.springframework.web.bind.annotation.*;

import java.io.FileInputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.cloud.firestore.*;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import com.todo.todo_backend.model.User;
import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.File;

import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // 允许前端跨域调用
public class TodoController {
    private synchronized void ensureFirebaseInitialized() {
        if (FirebaseApp.getApps().isEmpty()) {
            try {
                File file = new File("src/main/resources/serviceAccountKey.json");
                if (!file.exists()) {
                    throw new RuntimeException("❌ 找不到密钥文件: " + file.getAbsolutePath());
                }

                FileInputStream serviceAccount = new FileInputStream(file);
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();

                FirebaseApp.initializeApp(options);
                System.out.println("🔥🔥🔥 [Firebase] 云端数据库连接在接口调用时初始化成功！！！");
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("❌ Firebase 初始化失败: " + e.getMessage());
            }
        }
    }

    private final String DB_URL = "jdbc:sqlite:local_data.db";

    public TodoController() {
        // 构造函数：项目启动时自动创建本地 SQLite 数据库和任务表
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            Statement stmt = conn.createStatement();
            String sql = "CREATE TABLE IF NOT EXISTS local_tasks (" +
                         "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                         "task_name TEXT, " +
                         "status TEXT)";
            stmt.execute(sql);
            System.out.println("📦 [Java 后端] SQLite 本地数据库及任务表初始化成功！");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // 0. 健康检查接口
    @GetMapping("/health")
    public Map<String, Object> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("message", "Java Spring Boot 后端正常运行中！");
        return response;
    }

    // ==================== 1. CREATE (新增任务) ====================
    @PostMapping("/tasks")
    public Map<String, Object> addTask(@RequestBody Map<String, String> request) {
        String taskName = request.get("taskName");
        String insertSql = "INSERT INTO local_tasks(task_name, status) VALUES(?, ?)";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            pstmt.setString(1, taskName);
            pstmt.setString(2, "PENDING"); // 默认状态为待办
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "任务 [" + taskName + "] 创建成功！");
        return response;
    }

    // ==================== 2. READ (获取所有任务) ====================
    @GetMapping("/tasks")
    public List<Map<String, Object>> getAllTasks() {
        List<Map<String, Object>> tasks = new ArrayList<>();
        String selectSql = "SELECT * FROM local_tasks";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql)) {
            
            while (rs.next()) {
                Map<String, Object> task = new HashMap<>();
                task.put("id", rs.getInt("id"));
                task.put("taskName", rs.getString("task_name"));
                task.put("status", rs.getString("status"));
                tasks.add(task);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return tasks; // Spring Boot 会自动把这个 List 转成漂亮的 JSON 数组传给前端
    }

    // ==================== 3. UPDATE (更新任务状态，如打勾完成) ====================
    @PutMapping("/tasks/{id}")
    public Map<String, Object> updateTaskStatus(@PathVariable int id, @RequestBody Map<String, String> request) {
        String status = request.get("status"); // 传入 "COMPLETED" 或 "PENDING"
        String updateSql = "UPDATE local_tasks SET status = ? WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
            pstmt.setString(1, status);
            pstmt.setInt(2, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "ID 为 " + id + " 的任务状态已更新为 " + status);
        return response;
    }

    // ==================== 4. DELETE (删除任务) ====================
    @DeleteMapping("/tasks/{id}")
    public Map<String, Object> deleteTask(@PathVariable int id) {
        String deleteSql = "DELETE FROM local_tasks WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
            pstmt.setInt(1, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "ID 为 " + id + " 的任务已删除！");
        return response;
    }

    // ==================== 5. CLOUD (完成任务，云端积分 +10) ====================
    @PostMapping("/user/complete")
    public Map<String, Object> completeTaskAndAddPoints(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        Map<String, Object> response = new HashMap<>();

        if (username == null || username.trim().isEmpty()) {
            username = "TestUser";
        }

        try {
            // 1. 确保 Firebase 已经连上
            ensureFirebaseInitialized();

            // 2. 获取 Firebase Firestore 云端数据库实例
            Firestore db = FirestoreClient.getFirestore();
            
            // 3. 指向云端 "users" 集合中以该用户名命名的文档
            DocumentReference docRef = db.collection("users").document(username);
            
            // 4. 读取云端现有数据
            ApiFuture<DocumentSnapshot> future = docRef.get();
            DocumentSnapshot document = future.get();

            int currentPoints = 0;
            if (document.exists()) {
                Long pointsLong = document.getLong("points");
                if (pointsLong != null) {
                    currentPoints = pointsLong.intValue();
                }
            }

            // 5. 加分：积分 +10
            int newPoints = currentPoints + 10;

            // 6. 写入回 Firebase 云端数据库
            User updatedUser = new User(username, newPoints);
            docRef.set(updatedUser).get();

            response.put("success", true);
            response.put("username", username);
            response.put("totalPoints", newPoints);
            response.put("message", "任务完成！云端积分 +10，当前总积分: " + newPoints);
            System.out.println("🔥 [Firebase] 用户 [" + username + "] 积分更新成功，当前积分: " + newPoints);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return response;
    }
}