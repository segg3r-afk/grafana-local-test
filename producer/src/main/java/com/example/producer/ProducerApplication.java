package com.example.producer;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import jakarta.jms.Connection;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

@SpringBootApplication
public class ProducerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProducerApplication.class, args);
    }

    @Bean
    CommandLineRunner run(
            @Value("${activemq.brokerUrl}") String brokerUrl,
            @Value("${activemq.queue:demo.queue}") String queueName,
            @Value("${messages:10}") int messages
    ) {
        return args -> {
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(brokerUrl);
            try (Connection connection = connectionFactory.createConnection()) {
                connection.start();
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Queue queue = session.createQueue(queueName);
                MessageProducer producer = session.createProducer(queue);

                for (int i = 1; i <= messages; i++) {
                    TextMessage message = session.createTextMessage("msg-" + i);
                    producer.send(message);
                }

                producer.close();
                session.close();
            }
        };
    }
}


