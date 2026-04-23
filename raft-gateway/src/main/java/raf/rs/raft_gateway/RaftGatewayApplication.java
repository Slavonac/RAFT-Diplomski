package raf.rs.raft_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import raf.rs.RAFTGrpc;

@SpringBootApplication
@EnableScheduling
public class RaftGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(RaftGatewayApplication.class, args);

	}
}
