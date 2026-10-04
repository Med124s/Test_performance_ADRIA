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
    void semicolonSeparator_bomAndQuotes_areHandled() {
        List<Map<String, String>> rows = CsvDataSource.parse("﻿user;note\r\nagent001;\"a;b\"\r\nagent002;\"il dit \"\"oui\"\"\"");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("user", "agent001").containsEntry("note", "a;b");
        assertThat(rows.get(1)).containsEntry("note", "il dit \"oui\"");
    }

    @Test
    void commaWins_whenHeaderHasMoreCommas_andQuotedNewlinesAreKept() {
        List<Map<String, String>> rows = CsvDataSource.parse("a,b\n\"x;y\",\"ligne1\nligne2\"");

        assertThat(rows.get(0)).containsEntry("a", "x;y").containsEntry("b", "ligne1\nligne2");
        assertThat(CsvDataSource.validate("a,b\n\"x;y\",\"ligne1\nligne2\"")).isNull();
    }

    @Test
    void validate_reportsClearErrors() {
        assertThat(CsvDataSource.validate(null)).isNull();
        assertThat(CsvDataSource.validate("username,password\nu1,p1")).isNull();
        assertThat(CsvDataSource.validate("username,password")).contains("au moins une ligne de données");
        assertThat(CsvDataSource.validate("user name,x\na,b")).contains("Nom de colonne invalide « user name »");
        assertThat(CsvDataSource.validate("1col\na")).contains("Nom de colonne invalide");
        assertThat(CsvDataSource.validate("a,,b\n1,2,3")).contains("une colonne n'a pas de nom");
        assertThat(CsvDataSource.validate("a,a\n1,2")).contains("Colonne en double");
        assertThat(CsvDataSource.validate("a,b\n1,2\n\n3")).isEqualTo("Ligne 4 : 1 colonne(s) au lieu de 2.");
        assertThat(CsvDataSource.validate("a\n\"non fermé")).contains("Guillemet non fermé (ligne 2)");
        assertThat(CsvDataSource.validate("a\n" + "x".repeat(CsvDataSource.MAX_LENGTH))).contains("1 000 000");
    }

    @Test
    void valuesAndColumnNames_areTrimmed() {
        List<Map<String, String>> rows = CsvDataSource.parse(" username , password \n user1 , pass1 ");

        assertThat(rows.get(0)).containsEntry("username", "user1").containsEntry("password", "pass1");
    }
}
