package com.dreamapt.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.dreamapt.collect.DataGoKrProperties;
import com.dreamapt.geocode.JusoProperties;
import com.dreamapt.support.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

	@Autowired
	DataGoKrProperties dataGoKrProperties;

	@Autowired
	JusoProperties jusoProperties;

	@Test
	void contextLoads() {
	}

	// src/test/resources/application.properties가 환경변수의 실제 키를 덮는지 확인한다
	@Test
	void 테스트에서는_외부_API_키가_비어_있다() {
		assertThat(dataGoKrProperties.apiKey()).isNullOrEmpty();
		assertThat(jusoProperties.searchApiKey()).isNullOrEmpty();
		assertThat(jusoProperties.coordApiKey()).isNullOrEmpty();
	}

}
