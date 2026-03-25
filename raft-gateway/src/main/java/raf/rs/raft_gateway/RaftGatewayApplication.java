package raf.rs.raft_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import raf.rs.RAFTGrpc;

@SpringBootApplication
public class RaftGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(RaftGatewayApplication.class, args);

	}
}
