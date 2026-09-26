package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CsvDataSourceTest {

    @Test
    void nullCsv_returnsEmptyList() {
        assertThat(CsvDataSource.parse(null)).isEmpty();
    }

    @Test
    void blankCsv_returnsEmptyList() {
        assertThat(CsvDataSource.parse("   ")).isEmpty();
    }

    @Test
    void headerOnly_noDataRows_returnsEmptyList() {
        assertThat(CsvDataSource.parse("username,password")).isEmpty();
    }

    @Test
    void singleRow_parsesToOneMap() {
        List<Map<String, String>> rows = CsvDataSource.parse("username,password\nuser1,pass1");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("username", "user1").containsEntry("password", "pass1");
    }

    @Test
    void multipleRows_parsedInOrder() {
        List<Map<String, String>> rows = CsvDataSource.parse("username,password\nuser1,pass1\nuser2,pass2");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("username", "user1");
        assertThat(rows.get(1)).containsEntry("username", "user2");
    }

    @Test
    void blankLinesBetweenRows_areSkipped() {
        List<Map<String, String>> rows = CsvDataSource.parse("username\nuser1\n\nuser2");

        assertThat(rows).hasSize(2);
    }

    @Test
    void rowWithFewerColumnsThanHeader_missingColumnsAbsent_neverInvented() {
        List<Map<String, String>> rows = CsvDataSource.parse("username,password\nuser1");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("username", "user1");
        assertThat(rows.get(0)).doesNotContainKey("password");
    }

    @Test
    void rowWithMoreColumnsThanHeader_extraColumnsIgnored() {
        List<Map<String, String>> rows = CsvDataSource.parse("username\nuser1,extra-value");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("username", "user1");
        assertThat(rows.get(0)).hasSize(1);
    }

    @Test
    void valuesAndColumnNames_areTrimmed() {
        List<Map<String, String>> rows = CsvDataSource.parse(" username , password \n user1 , pass1 ");

        assertThat(rows.get(0)).containsEntry("username", "user1").containsEntry("password", "pass1");
    }
}
