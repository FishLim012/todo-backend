package com.todo.todo_backend;

import java.io.FileInputStream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import javax.annotation.PostConstruct;
import java.io.File;

@SpringBootApplication
public class TodoBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(TodoBackendApplication.class, args);
	}

	

}
