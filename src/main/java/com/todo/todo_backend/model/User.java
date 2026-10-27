package com.todo.todo_backend.model;

public class User {
    private String username;
    private int points;

    // 无参构造函数（Firebase 反序列化时必须）
    public User() {}

    public User(String username, int points) {
        this.username = username;
        this.points = points;
    }

    // Getters and Setters
    public String getUsername() { 
        return username; 
    }
    public void setUsername(String username) { 
        this.username = username; 
    }

    public int getPoints() { 
        return points; 
    }
    public void setPoints(int points) { 
        this.points = points; 
    }
}