package dev.rashed.dispatch.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rashed.dispatch.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class CompanyRepositoryTests {

    @Autowired
    CompanyRepository companies;

    @Test
    void findsCompanyBySlug() {
        companies.saveAndFlush(Company.create("City Bank PLC", "city-bank"));

        assertThat(companies.findBySlug("city-bank"))
                .get()
                .extracting(Company::name)
                .isEqualTo("City Bank PLC");
    }
}
