package edu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class LogAnalyzer {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static void main(String[] args) {
        String path = args[0];
        OffsetDateTime from = null; // parse from argument if provided
        OffsetDateTime to = null; // parse to argument if provided
        String format = "markdown"; // default format is markdown

        if (args.length > 1) {
            from = OffsetDateTime.parse(args[1]);
            to = OffsetDateTime.parse(args[2]);
            format = args[3];
        }

        try {
            Stream<LogRecord> logRecords = readLogRecords(path);
            LogReport logReport = generateLogReport(logRecords, from, to);
            String output = format.equals("adoc") ? generateAdocReport(logReport) : generateMarkdownReport(logReport);
            System.out.println(output);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Stream<LogRecord> readLogRecords(String path) throws IOException {
        if (path.startsWith("http")) { // read logs from URL
            URL url = new URL(path);
            BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
            return reader.lines().map(LogRecord::parse);
        } else { // read logs from local files
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + path);
            return Files.walk(Paths.get("."))
                    .filter(matcher::matches)
                    .flatMap(LogAnalyzer::readLogFile);
        }
    }

    private static Stream<LogRecord> readLogFile(Path file) {
        try {
            return Files.lines(file).map(LogRecord::parse);
        } catch (IOException e) {
            e.printStackTrace();
            return Stream.empty();
        }
    }

    private static LogReport generateLogReport(Stream<LogRecord> logRecords, OffsetDateTime from, OffsetDateTime to) {
        List<LogRecord> filteredRecords = logRecords
                .filter(record -> from == null || record.getTimestamp().isAfter(from))
                .filter(record -> to == null || record.getTimestamp().isBefore(to))
                .collect(Collectors.toList());

        LogReport report = new LogReport();
        report.setFromDate(from);
        report.setToDate(to);
        report.setTotalRequests(filteredRecords.size());

        if (!filteredRecords.isEmpty()) {
            double totalResponseSize = filteredRecords.stream()
                    .mapToLong(LogRecord::getResponseSize)
                    .sum();
            double averageResponseSize = totalResponseSize / filteredRecords.size();
            report.setAverageResponseSize(averageResponseSize);

            Map<String, Integer> resourceCounts = new HashMap<>();
            Map<Integer, String> responseCodeNames = new HashMap<>();
            Map<Integer, Integer> responseCodeCounts = new HashMap<>();

            for (LogRecord record : filteredRecords) {
                String resource = getResourceFromRequest(record.getRequest());
                resourceCounts.put(resource, resourceCounts.getOrDefault(resource, 0) + 1);

                int statusCode = record.getStatusCode();
                String statusName = getStatusName(statusCode);
                responseCodeNames.putIfAbsent(statusCode, statusName);
                responseCodeCounts.put(statusCode, responseCodeCounts.getOrDefault(statusCode, 0) + 1);
            }

            report.setMostRequestedResources(getTopN(resourceCounts, 3));
            report.setMostFrequentResponseCodes(getTopN(responseCodeNames, responseCodeCounts, 3));
        }

        return report;
    }

    private static Map<Integer, String> getTopN(Map<Integer, String> responseCodeNames, Map<Integer, Integer> responseCodeCounts, int i) {
        return responseCodeNames;
    }

    private static String getResourceFromRequest(String request) {
        String[] parts = request.split(" ");
        if (parts.length > 1) {
            return parts[1];
        }
        return "";
    }

    private static String getStatusName(int statusCode) {
        switch (statusCode) {
            case 200:
                return "OK";
            case 404:
                return "Not Found";
            case 500:
                return "Internal Server Error";
            default:
                return "Unknown";
        }
    }

    private static <K, V extends Comparable<? super V>> Map<K, V> getTopN(Map<K, V> map, int n) {
        return map.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(n)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue, LinkedHashMap::new));
    }

    private static String generateMarkdownReport(LogReport logReport) {
        StringBuilder sb = new StringBuilder();

        sb.append("#### Общая информация\n\n");
        sb.append("| Метрика | Значение |\n");
        sb.append("|:-------:|---------:|\n");
        sb.append("| Файл(-ы) | `access.log` |\n");

        if (logReport.getFromDate() != null) {
            sb.append("| Начальная дата | ").append(logReport.getFromDate().format(DATE_TIME_FORMATTER)).append(" |\n");

            if (logReport.getToDate() != null) {
                sb.append("| Конечная дата | ").append(logReport.getToDate().format(DATE_TIME_FORMATTER)).append(" |\n");
            } else {
                sb.append("| Конечная дата | - |\n");
            }
        } else {
            sb.append("| Начальная дата | - |\n");
            sb.append("| Конечная дата | - |\n");
        }

        sb.append("| Количество запросов | ").append(logReport.getTotalRequests()).append(" |\n");

        if (logReport.getAverageResponseSize() != null) {
            sb.append("| Средний размер ответа сервера | ").append(logReport.getAverageResponseSize()).append("b |\n");
        }

        sb.append("\n#### Запрашиваемые ресурсы\n\n");
        sb.append("| Ресурс | Количество |\n");
        sb.append("|:------:|----------:|\n");

        for (Map.Entry<String, Integer> entry : logReport.getMostRequestedResources().entrySet()) {
            sb.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
        }

        sb.append("\n#### Коды ответа\n\n");
        sb.append("| Код | Имя | Количество |\n");
        sb.append("|:---:|:---:|----------:|\n");

        for (Map.Entry<Integer, String> entry : logReport.getMostFrequentResponseCodes().entrySet()) {
            int code = entry.getKey();
            String name = entry.getValue();
            int count = logReport.getResponseCodeCount(code);
            sb.append("| ").append(code).append(" | ").append(name).append(" | ").append(count).append(" |\n");
        }

        return sb.toString();
    }

    private static String generateAdocReport(LogReport logReport) {
        // Generate the report in AsciiDoc format
        // Implement this method as needed
        return "";
    }
}

class LogRecord {
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private final String remoteAddress;
    private final String remoteUser;
    private final OffsetDateTime timestamp;
    private final String request;
    private final int statusCode;
    private final long responseSize;
    private final String referer;
    private final String userAgent;

    public LogRecord(String remoteAddress, String remoteUser, OffsetDateTime timestamp, String request,
                     int statusCode, long responseSize, String referer, String userAgent) {
        this.remoteAddress = remoteAddress;
        this.remoteUser = remoteUser;
        this.timestamp = timestamp;
        this.request = request;
        this.statusCode = statusCode;
        this.responseSize = responseSize;
        this.referer = referer;
        this.userAgent = userAgent;
    }

    public static LogRecord parse(String line) {
        String[] parts = line.split(" ");
        if (parts.length >= 8) {
            String remoteAddress = parts[0];
            String remoteUser = parts[1];
            String timestampString = parts[3] + " " + parts[4];
            OffsetDateTime timestamp = OffsetDateTime.parse(timestampString, DATE_TIME_FORMATTER);
            String request = parts[5];
            int statusCode = Integer.parseInt(parts[6]);
            long responseSize = Long.parseLong(parts[7]);
            String referer = (parts.length > 8) ? parts[8] : "";
            String userAgent = (parts.length > 9) ? parts[9] : "";

            return new LogRecord(remoteAddress, remoteUser, timestamp, request, statusCode, responseSize, referer, userAgent);
        } else {
            throw new IllegalArgumentException("Invalid log record format: " + line);
        }
    }

    // Getters for the log record fields
    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public String getRequest() {
        return request;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public long getResponseSize() {
        return responseSize;
    }
}

class LogReport {
    private OffsetDateTime fromDate;
    private OffsetDateTime toDate;
    private int totalRequests;
    private Double averageResponseSize;
    private Map<String, Integer> mostRequestedResources;
    private Map<Integer, String> mostFrequentResponseCodes;

    public OffsetDateTime getFromDate() {
        return fromDate;
    }

    public void setFromDate(OffsetDateTime fromDate) {
        this.fromDate = fromDate;
    }

    public OffsetDateTime getToDate() {
        return toDate;
    }

    public void setToDate(OffsetDateTime toDate) {
        this.toDate = toDate;
    }

    public int getTotalRequests() {
        return totalRequests;
    }

    public void setTotalRequests(int totalRequests) {
        this.totalRequests = totalRequests;
    }

    public Double getAverageResponseSize() {
        return averageResponseSize;
    }

    public void setAverageResponseSize(Double averageResponseSize) {
        this.averageResponseSize = averageResponseSize;
    }

    public Map<String, Integer> getMostRequestedResources() {
        return mostRequestedResources;
    }

    public void setMostRequestedResources(Map<String, Integer> mostRequestedResources) {
        this.mostRequestedResources = mostRequestedResources;
    }

    public Map<Integer, String> getMostFrequentResponseCodes() {
        return mostFrequentResponseCodes;
    }

    public void setMostFrequentResponseCodes(Map<Integer, String> mostFrequentResponseCodes) {
        this.mostFrequentResponseCodes = mostFrequentResponseCodes;
    }

    // Method to get the count of a specific response code
    public int getResponseCodeCount(int statusCode) {
        if (mostFrequentResponseCodes.containsKey(statusCode)) {
            String statusName = mostFrequentResponseCodes.get(statusCode);
            return mostFrequentResponseCodes.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(statusName))
                    .mapToInt(Map.Entry::getKey)
                    .sum();
        }
        return 0;
    }
}
