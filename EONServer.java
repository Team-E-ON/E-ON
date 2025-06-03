package dbTransaction2025;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EONServer {

    static final String DB_URL = "jdbc:mysql://localhost:3306/DB2025Team06";
    static final String USER = "DB2025Team06";
    static final String PASS = "DB2025Team06";

    // 세션 ID → 사용자 ID 매핑
    private static final Map<String, String> sessionMap = new HashMap<>();
    private static final Map<String, String> sessionRoleMap = new HashMap<>(); // 관리자 역할 확인용

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "/home");
            exchange.sendResponseHeaders(302, -1); // 302 Found 리디렉션
            exchange.close();
        });

        server.createContext("/home", EONServer::handleHome);
        server.createContext("/mypage", EONServer::handleMypage);
        server.createContext("/login.html", ex -> serveStaticFile(ex, "login.html", "text/html"));
        server.createContext("/signup.html", ex -> serveStaticFile(ex, "signup.html", "text/html"));
        server.createContext("/login", EONServer::handleLogin);
        server.createContext("/signup", EONServer::handleSignup);
        server.createContext("/logout", EONServer::handleLogout);

        server.createContext("/Home.css", ex -> serveStaticFile(ex, "Home.css", "text/css"));
        server.createContext("/mypage.css", ex -> serveStaticFile(ex, "mypage.css", "text/css"));
        server.createContext("/login.css", ex -> serveStaticFile(ex, "login.css", "text/css"));
        server.createContext("/signup.css", ex -> serveStaticFile(ex, "signup.css", "text/css"));

        server.createContext("/mypage.html/change-password", EONServer::handleChangePassword);
        server.createContext("/mypage.html/delete-user", EONServer::handleDeleteUser);

        // ★ 관리자 일정 관리 페이지 (Server, ScheduleServer의 기능 통합)
        server.createContext("/scheduleadmin", EONServer::handleScheduleAdmin);       // 관리자 전용
        server.createContext("/scheduleadmin/groups", EONServer::handleScheduleGroups); // 학과/동아리 목록
        server.createContext("/scheduleadmin/add", EONServer::handleAddEvent);       // 일정 추가
        server.createContext("/scheduleadmin/delete", EONServer::handleDeleteEvent); // 일정 삭제
        server.createContext("/scheduleadmin/edit", EONServer::handleEditEvent);     // 일정 수정

        // schedule.css 정적 파일 서빙
        server.createContext("/schedule.css", exchange -> {
            File cssFile = new File("schedule.css"); // ← 파일이 EONServer.java와 같은 폴더라면 이렇게
            byte[] cssBytes = Files.readAllBytes(cssFile.toPath());
            exchange.getResponseHeaders().add("Content-Type", "text/css");
            exchange.sendResponseHeaders(200, cssBytes.length);
            exchange.getResponseBody().write(cssBytes);
            exchange.close();
        });
        server.createContext("/schedule", EONServer::handleSchedulePage);  // 👈 이거 추가!
        server.createContext("/schedule_user.html", ex -> serveStaticFile(ex, "schedule_user.html", "text/html"));
        server.createContext("/schedule_user", EONServer::handleScheduleUser);

        server.createContext("/event/like", EONServer::handleEventLike);


        server.setExecutor(null);
        server.start();
        System.out.println("서버 실행 중: http://localhost:8080");
    }

    // 정적 파일 서빙: HTML, CSS, JS 파일 등을 클라이언트에게 전달
    private static void serveStaticFile(HttpExchange exchange, String fileName, String contentType) throws IOException {
        byte[] bytes = Files.readAllBytes(new File(fileName).toPath()); // 파일을 바이트 배열로 읽기
        exchange.getResponseHeaders().add("Content-Type", contentType); // 응답 헤더에 Content-Type 추가
        exchange.sendResponseHeaders(200, bytes.length); // 200 OK 응답과 본문 길이 전송
        exchange.getResponseBody().write(bytes); // 파일 내용 전송
        exchange.close(); // 연결 종료
    }

    // 쿠키에서 sessionId 추출 후 사용자 ID 반환
    private static String getUserIdFromCookie(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies != null) {
            for (String cookie : cookies) {
                for (String pair : cookie.split(";")) {
                    String[] kv = pair.trim().split("=");
                    if (kv.length == 2 && kv[0].equals("sessionId")) {
                        return sessionMap.get(kv[1]); // 세션 맵에서 사용자 ID 반환
                    }
                }
            }
        }
        return null; // 유효한 세션이 없으면 null 반환
    }

    // 이벤트 좋아요/취소 처리
    private static void handleEventLike(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1); // POST 방식이 아닌 경우 405 반환
            return;
        }

        String userId = getUserIdFromCookie(exchange);
        if (userId == null) {
            exchange.sendResponseHeaders(401, -1); // 로그인하지 않은 사용자
            return;
        }

        // 요청 본문 파싱
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .lines().reduce("", (acc, line) -> acc + line);
        Map<String, String> params = queryToMap(body);
        String eventIdStr = params.get("eventId");
        String likeStr = params.get("like");

        if (eventIdStr == null || likeStr == null) {
            exchange.sendResponseHeaders(400, -1); // 필수 파라미터 누락 시 400
            return;
        }

        int eventId = Integer.parseInt(eventIdStr);
        boolean like = Boolean.parseBoolean(likeStr);

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            if (like) {
                // 좋아요 추가
                String generatedId = UUID.randomUUID().toString();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT IGNORE INTO DB2025_EVENT_LIKE (id, user_id, event_id) VALUES (?, ?, ?)")) {
                    stmt.setString(1, generatedId);
                    stmt.setString(2, userId);
                    stmt.setInt(3, eventId);
                    stmt.executeUpdate();
                }
            } else {
                // 좋아요 취소
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM DB2025_EVENT_LIKE WHERE user_id = ? AND event_id = ?")) {
                    stmt.setString(1, userId);
                    stmt.setInt(2, eventId);
                    stmt.executeUpdate();
                }
            }

            exchange.sendResponseHeaders(200, -1); // 성공 응답
        } catch (SQLException e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1); // 서버 오류
        }
    }

    // 홈 화면 렌더링: 캘린더와 D-DAY 표시
    private static void handleHome(HttpExchange exchange) throws IOException {
        String userId = getUserIdFromCookie(exchange);
        if (userId == null) {
            // 로그인 안 된 경우 로그인 페이지로 리다이렉트
            exchange.getResponseHeaders().add("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        String html = new String(Files.readAllBytes(new File("home.html").toPath())); // HTML 템플릿 읽기
        StringBuilder ddayItems = new StringBuilder(); // D-Day 항목
        StringBuilder calendarTable = new StringBuilder(); // 달력 HTML

        String role = getUserRoleFromCookie(exchange);
        if ("admin".equals(role)) {
            // 관리자일 경우 관리자 화면으로 리다이렉트
            exchange.getResponseHeaders().add("Location", "/scheduleadmin");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            // 날짜별 좋아요한 이벤트 수집
            Map<Integer, List<String>> eventsByDay = new HashMap<>();
            String eventQuery = """
            SELECT DAY(date) AS day, title, type
            FROM DB2025_LIKED_EVENTS_VIEW
            WHERE user_id = ?
              AND MONTH(date) = MONTH(CURRENT_DATE)
              AND YEAR(date) = YEAR(CURRENT_DATE)
        """;
            try (PreparedStatement stmt = conn.prepareStatement(eventQuery)) {
                stmt.setString(1, userId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    int day = rs.getInt("day");
                    String title = rs.getString("title");
                    String type = rs.getString("type");
                    String color = switch (type) {
                        case "department" -> "green";
                        case "club" -> "yellow";
                        default -> "gray";
                    };
                    eventsByDay.computeIfAbsent(day, k -> new ArrayList<>())
                            .add("<div class='event " + color + "'>" + title + "</div>");
                }
            }

            // D-DAY 목록 조회
            String ddayQuery = """
            SELECT title, DATEDIFF(date, CURRENT_DATE) AS d_day, type
            FROM DB2025_LIKED_EVENTS_VIEW
            WHERE user_id = ?
              AND date >= CURRENT_DATE
            ORDER BY date
        """;
            try (PreparedStatement stmt = conn.prepareStatement(ddayQuery)) {
                stmt.setString(1, userId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String title = rs.getString("title");
                    int dDay = rs.getInt("d_day");
                    String type = rs.getString("type");
                    String color = switch (type) {
                        case "department" -> "green";
                        case "club" -> "goldenrod";
                        default -> "black";
                    };
                    ddayItems.append("<li style='color:").append(color).append(";'>")
                            .append("<span class='dot ").append(color).append("'></span> ")
                            .append(title).append(" <span class='dday-num'>D-").append(dDay).append("</span></li>");
                }
            }

            // 현재 날짜 기준으로 달력 구성
            LocalDate today = LocalDate.now();
            int year = today.getYear(), month = today.getMonthValue(), todayDay = today.getDayOfMonth();
            LocalDate firstDay = LocalDate.of(year, month, 1);
            int startDay = firstDay.getDayOfWeek().getValue(); // 1 (월요일) ~ 7 (일요일)
            int totalDays = firstDay.lengthOfMonth(); // 이번 달 총 일수

            int cellCount = 0;
            calendarTable.append("<tr>");

            // 달력 제목 삽입
            String calendarTitle = today.getMonth().toString().substring(0, 1).toUpperCase() +
                    today.getMonth().toString().substring(1).toLowerCase() + " " + year;
            html = html.replace("{{calendarTitle}}", calendarTitle);

            // 빈 칸 채우기 (달의 시작 요일 전까지)
            for (int i = 1; i < startDay; i++, cellCount++) {
                calendarTable.append("<td class='empty'></td>");
            }

            // 날짜 및 이벤트 삽입
            for (int day = 1; day <= totalDays; day++, cellCount++) {
                if (cellCount % 7 == 0 && cellCount != 0) {
                    calendarTable.append("</tr><tr>");
                }
                boolean isToday = (day == todayDay);
                calendarTable.append("<td");
                if (isToday) {
                    calendarTable.append(" class='today'");
                }
                calendarTable.append(">" + String.format("%02d", day));
                if (eventsByDay.containsKey(day)) {
                    eventsByDay.get(day).forEach(calendarTable::append);
                }
                calendarTable.append("</td>");
            }

            // 마지막 줄 빈 칸 채우기
            while (cellCount % 7 != 0) {
                calendarTable.append("<td class='empty'></td>");
                cellCount++;
            }
            calendarTable.append("</tr>");

        } catch (SQLException e) {
            e.printStackTrace(); // DB 예외 처리
        }

        // 템플릿 HTML에 데이터 삽입
        html = html.replace("{{calendarTable}}", calendarTable.toString())
                .replace("{{ddayList}}", ddayItems.toString());

        // 응답 전송
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html.getBytes());
        }
    }

    // 마이페이지 요청 처리
    private static void handleMypage(HttpExchange exchange) throws IOException {
        // 쿠키에서 사용자 ID 가져오기
        String id = getUserIdFromCookie(exchange);
        if (id == null) {
            // 로그인하지 않은 사용자는 로그인 페이지로 리다이렉트
            exchange.getResponseHeaders().add("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        // 마이페이지 HTML 읽기
        String html = new String(Files.readAllBytes(new File("mypage.html").toPath()));

        // 사용자 정보 초기값 설정
        String name = "", major = "", subMajors = "", clubs = "";

        // 사용자 요약 정보를 조회하는 SQL
        String query = """
                SELECT name, id, major, minors AS sub_majors, clubs
                FROM DB2025_USER_SUMMARY_VIEW
                WHERE id = ?
            """;

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                name = rs.getString("name");
                major = rs.getString("major");
                subMajors = rs.getString("sub_majors");
                clubs = rs.getString("clubs");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // 사용자 정보를 HTML에 삽입
        html = html.replace("{{name}}", name)
                .replace("{{id}}", id)
                .replace("{{major}}", major != null ? major : "없음")
                .replace("{{subMajors}}", subMajors != null ? subMajors : "없음")
                .replace("{{clubs}}", clubs != null ? clubs : "없음");

        // 응답 전송
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html.getBytes());
        }
    }

    // 로그인 요청 처리
    private static void handleLogin(HttpExchange exchange) throws IOException {
        // POST 요청만 허용
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 폼 데이터 읽기
        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(isr);
        String formData = reader.readLine();
        if (formData == null || formData.isBlank()) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        // 파라미터 파싱
        Map<String, String> params = new HashMap<>();
        for (String pair : formData.split("&")) {
            String[] parts = pair.split("=");
            if (parts.length >= 2) {
                params.put(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8"));
            }
        }

        // 로그인 시 입력된 id, password 가져오기
        String id = params.get("id");
        String pw = params.get("password");

        // 사용자 정보 조회 SQL
        String sql = "SELECT password, role FROM DB2025_USER WHERE id = ?";

        String role = null;
        boolean success = false;
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                success = rs.getString("password").equals(pw); // 비밀번호 일치 여부 확인
                role = rs.getString("role"); // 역할도 함께 저장
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (success) {
            // 로그인 성공 → 세션 생성
            String sessionId = UUID.randomUUID().toString();
            sessionMap.put(sessionId, id);
            sessionRoleMap.put(sessionId, role); // 역할 정보 저장
            exchange.getResponseHeaders().add("Set-Cookie", "sessionId=" + sessionId + "; Path=/");
            exchange.getResponseHeaders().add("Location", "/home"); // 홈으로 이동
            exchange.sendResponseHeaders(302, -1);
        } else {
            // 로그인 실패 → 다시 로그인 페이지로 이동
            exchange.getResponseHeaders().add("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1);
        }
        exchange.close();
    }

    // 로그아웃 처리
    private static void handleLogout(HttpExchange exchange) throws IOException {
        // 세션 쿠키 무효화
        exchange.getResponseHeaders().add("Set-Cookie", "sessionId=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT");

        // 세션맵에서도 삭제
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies != null) {
            for (String cookie : cookies) {
                for (String pair : cookie.split(";")) {
                    String[] kv = pair.trim().split("=");
                    if (kv.length == 2 && kv[0].equals("sessionId")) {
                        sessionMap.remove(kv[1]);
                        break;
                    }
                }
            }
        }

        // 로그인 페이지로 리디렉션
        exchange.getResponseHeaders().add("Location", "/login.html");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    // 회원가입 요청 처리
    private static void handleSignup(HttpExchange exchange) throws IOException {
        // POST 요청만 허용
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // 폼 데이터 읽기
        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(isr);
        String formData = reader.readLine();

        // 파라미터 파싱
        Map<String, List<String>> params = new HashMap<>();
        for (String pair : formData.split("&")) {
            String[] parts = pair.split("=");
            String key = URLDecoder.decode(parts[0], "UTF-8");
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], "UTF-8") : "";
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }

        // 폼 데이터 → 변수로 매핑
        long id = Long.parseLong(params.get("id").get(0));
        String name = params.get("name").get(0);
        String password = params.get("password").get(0);
        long majorId = mapMajor(params.get("major").get(0));
        List<Long> minors = mapAll(params.getOrDefault("minors[]", List.of()));
        List<Long> clubs = mapAll(params.getOrDefault("clubs[]", List.of()));

        // 사용자 정보 DB에 저장
        boolean success = insertUserAll(id, name, password, majorId, minors, clubs);

        // 결과 응답
        String res = success ? "{\"success\":true}" : "{\"success\":false}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, res.getBytes().length);
        exchange.getResponseBody().write(res.getBytes());
        exchange.close();
    }

    // 문자열 리스트를 ID 리스트로 변환
    private static List<Long> mapAll(List<String> names) {
        List<Long> list = new ArrayList<>();
        for (String n : names) {
            long id = n.matches("\\d+") ? Long.parseLong(n) : mapMajor(n); // 숫자면 그대로, 아니면 전공 이름 매핑
            if (id > 0) {
                list.add(id);
            }
        }
        return list;
    }

    // 전공 이름을 툭정 숫자로 변환
    private static long mapMajor(String name) {
        return switch (name) {
            case "컴퓨터공학과" -> 1;
            case "사이버보안학과" -> 2;
            case "인공지능전공" -> 3;
            case "데이터사이언스전공" -> 4;
            default -> -1; // 일치하는 전공 없으면 -1 반환
        };
    }

    // 사용자 전체 등록 메서드 (전공, 복수전공, 동아리까지 포함)
    private static boolean insertUserAll(long id, String name, String password, long majorId, List<Long> minors,
                                         List<Long> clubs) {
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            conn.setAutoCommit(false); // 트랜잭션 시작

            if (checkUserExists(conn, id)) { // 사용자 중복 확인
                return false;
            }

            insertUser(conn, id, name, password); // 사용자 기본 정보 삽입
            insertDepartment(conn, id, majorId, "major", "ud_major"); // 전공 삽입

            // 복수전공 삽입
            for (int i = 0; i < minors.size(); i++) {
                insertDepartment(conn, id, minors.get(i), "minor", "ud_minor_" + i);
            }

            // 동아리 삽입
            for (int i = 0; i < clubs.size(); i++) {
                insertClub(conn, id, clubs.get(i), "uc_" + i);
            }

            conn.commit(); // 트랜잭션 커밋
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // 사용자 존재 여부 확인
    private static boolean checkUserExists(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM DB2025_USER WHERE id = ?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    // 사용자 삽입
    private static void insertUser(Connection conn, long id, String name, String password) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO DB2025_USER (id, name, password, role) VALUES (?, ?, ?, 'user')")) {
            ps.setLong(1, id);
            ps.setString(2, name);
            ps.setString(3, password);
            ps.executeUpdate();
        }
    }

    // 전공 또는 복수전공 삽입
    private static void insertDepartment(Connection conn, long userId, long deptId, String type, String genId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO DB2025_USER_DEPARTMENT (id, user_id, department_id, major_type) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, genId);
            ps.setLong(2, userId);
            ps.setLong(3, deptId);
            ps.setString(4, type);
            ps.executeUpdate();
        }
    }

    // 동아리 삽입
    private static void insertClub(Connection conn, long userId, long clubId, String genId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO DB2025_USER_CLUB (id, user_id, club_id) VALUES (?, ?, ?)")) {
            ps.setString(1, genId);
            ps.setLong(2, userId);
            ps.setLong(3, clubId);
            ps.executeUpdate();
        }
    }

    // 비밀번호 변경 처리
    private static void handleChangePassword(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1); // 허용되지 않은 메서드
            return;
        }

        // 요청 본문 읽기
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .lines().reduce("", (acc, line) -> acc + line);
        Map<String, String> params = parseQuery(body);

        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");
        String userId = params.get("userId");

        if (oldPassword == null || newPassword == null || userId == null) {
            exchange.sendResponseHeaders(400, -1); // 잘못된 요청
            return;
        }

        String queryCheck = "SELECT password FROM DB2025_USER WHERE id = ?";
        String queryUpdate = "UPDATE DB2025_USER SET password = ? WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement stmtCheck = conn.prepareStatement(queryCheck)) {

            stmtCheck.setString(1, userId);
            ResultSet rs = stmtCheck.executeQuery();

            if (rs.next()) {
                String currentPassword = rs.getString("password");

                if (!currentPassword.equals(oldPassword)) {
                    // 기존 비밀번호가 일치하지 않음
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }

                // 비밀번호 변경 실행
                try (PreparedStatement stmtUpdate = conn.prepareStatement(queryUpdate)) {
                    stmtUpdate.setString(1, newPassword);
                    stmtUpdate.setString(2, userId);
                    int updated = stmtUpdate.executeUpdate();

                    if (updated > 0) {
                        exchange.sendResponseHeaders(200, -1); // 성공
                    } else {
                        exchange.sendResponseHeaders(500, -1); // 실패
                    }
                }
            } else {
                exchange.sendResponseHeaders(404, -1); // 사용자 없음
            }

        } catch (SQLException e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1); // 서버 오류
        }
    }

    // 쿼리 문자열을 key-value Map으로 파싱
    private static Map<String, String> parseQuery(String query) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                map.put(URLDecoder.decode(pair[0], "UTF-8"), URLDecoder.decode(pair[1], "UTF-8"));
            }
        }
        return map;
    }

    // 회원 탈퇴 처리
    private static void handleDeleteUser(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String loggedInUserId = getUserIdFromCookie(exchange); // 현재 로그인된 사용자 확인
        if (loggedInUserId == null) {
            exchange.getResponseHeaders().add("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1); // 로그인 페이지로 리디렉션
            return;
        }

        // 요청 본문에서 삭제할 사용자 ID 가져오기
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .lines().reduce("", (acc, line) -> acc + line);
        Map<String, String> params = parseQuery(body);
        String userIdToDelete = params.get("userId");

        if (userIdToDelete == null) {
            exchange.sendResponseHeaders(400, -1); // 잘못된 요청
            return;
        }

        // 자신의 계정만 탈퇴 가능
        if (!loggedInUserId.equals(userIdToDelete)) {
            exchange.sendResponseHeaders(403, -1); // 권한 없음
            return;
        }

        String query = "DELETE FROM DB2025_USER WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, userIdToDelete);
            int deleted = stmt.executeUpdate();

            if (deleted > 0) {
                // 세션 쿠키 삭제 및 세션맵 정리
                exchange.getResponseHeaders()
                        .add("Set-Cookie", "sessionId=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                sessionMap.remove(loggedInUserId);
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(404, -1); // 사용자 없음
            }
        } catch (SQLException e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1); // 서버 오류
        }
    }

    // 일정 페이지 처리 (로그인 사용자의 일정 출력)
    private static void handleSchedulePage(HttpExchange exchange) throws IOException {
        File htmlFile = new File("schedule-server/schedule.html");
        String html = new String(Files.readAllBytes(htmlFile.toPath()));
        StringBuilder eventHtml = new StringBuilder();

        try (
                Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
                PreparedStatement stmt = conn.prepareStatement(
                        "SELECT title, date, group_name " +
                                "FROM DB2025_user_schedule_view " +
                                "WHERE user_id = ? " +
                                "ORDER BY group_name, date"
                )
        ) {
            stmt.setLong(1, 101L); // 테스트용 사용자 ID

            ResultSet rs = stmt.executeQuery();
            Map<String, List<String>> groupMap = new LinkedHashMap<>();

            while (rs.next()) {
                String title = rs.getString("title");
                String date = rs.getDate("date").toString();
                String group = rs.getString("group_name");

                String itemHtml = "<div class='schedule-item'>" +
                        "<div class='item-left'>" +
                        "<span class='item-title bold'>" + title + "</span>" +
                        "<span class='item-date'>" + date + "</span>" +
                        "</div>" +
                        "<span class='item-actions'>" +
                        "<button class='heart-btn'>♡</button>" +
                        "</span></div>";

                groupMap.computeIfAbsent(group, k -> new ArrayList<>()).add(itemHtml);
            }

            // 그룹별로 HTML 구성
            for (Map.Entry<String, List<String>> entry : groupMap.entrySet()) {
                String groupName = entry.getKey();
                List<String> items = entry.getValue();

                eventHtml.append("<div class='schedule-group'>")
                        .append("<div class='group-title'>").append(groupName).append(" 일정</div>");

                for (String item : items) {
                    eventHtml.append(item);
                }

                eventHtml.append("</div>");
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        html = html.replace("{events}", eventHtml.toString());

        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(html.getBytes());
        os.close();
    }

    // 쿠키에서 사용자 역할(role) 가져오기
    private static String getUserRoleFromCookie(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies != null) {
            for (String cookie : cookies) {
                for (String pair : cookie.split(";")) {
                    String[] kv = pair.trim().split("=");
                    if (kv.length == 2 && kv[0].equals("sessionId")) {
                        return sessionRoleMap.get(kv[1]);
                    }
                }
            }
        }
        return null;
    }

    // 관리자 일정 페이지 요청을 처리하는 메서드
    private static void handleScheduleAdmin(HttpExchange exchange) throws IOException {
        System.out.println("Working dir: " + new File(".").getAbsolutePath());

        // HTML 템플릿 파일 읽기
        String html = new String(Files.readAllBytes(new File("schedule_admin.html").toPath()));
        String name = "", major = "", subMajors = "", clubs = "";
        String departmentHtml = "", clubHtml = "";

        // 학과별 이벤트 조회 쿼리
        String departmentEventQuery = "SELECT e.id, d.name, d.college_name, e.title, e.date, e.content "
                + "FROM DB2025_EVENT e " + "JOIN DB2025_DEPARTMENT d ON e.ref_id = d.id "
                + "WHERE e.type = 'department' " + "ORDER BY d.name, e.date";

        // 동아리별 이벤트 조회 쿼리
        String clubEventQuery = "SELECT e.id, c.name, e.title, e.date, e.content "
                + "FROM DB2025_EVENT e " + "JOIN DB2025_CLUB c ON e.ref_id = c.id "
                + "WHERE e.type = 'club' " + "ORDER BY c.name, e.date";

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            // 학과별 일정 HTML 구성
            try (PreparedStatement stmt = conn.prepareStatement(departmentEventQuery)) {
                ResultSet rs = stmt.executeQuery();
                String currentDepartment = "";
                StringBuilder sb = new StringBuilder();

                while (rs.next()) {
                    String deptName = rs.getString("name") + " (" + rs.getString("college_name") + ")";
                    // 새 학과 이름이 나오면 이전 블록 닫고 새 블록 시작
                    if (!deptName.equals(currentDepartment)) {
                        if (!currentDepartment.isEmpty()) {
                            sb.append("</div>"); // 이전 그룹 닫기
                        }
                        currentDepartment = deptName;
                        sb.append("<h2>").append(deptName).append("</h2><div class=\"schedule-group\">");
                    }

                    // 개별 일정 항목 HTML 구성
                    sb.append("<div class=\"schedule-item\">")
                            .append("<div class=\"item-left\">")
                            .append("<span class=\"item-title\">").append(rs.getString("title")).append("</span>")
                            .append("<span class=\"item-date\">").append(rs.getString("date")).append("</span>")
                            .append("<div class=\"item-content\">").append(rs.getString("content")).append("</div>")
                            .append("<span class=\"event-id\" style=\"display:none;\">").append(rs.getInt("id")).append("</span>")
                            .append("</div>")
                            .append("<span class=\"item-actions\">")
                            .append("<button class=\"edit-btn\">✎</button>")
                            .append("<button class=\"delete-btn\">🗑</button>")
                            .append("</span>")
                            .append("</div>");
                }

                if (!currentDepartment.isEmpty()) {
                    sb.append("</div>"); // 마지막 그룹 닫기
                }

                departmentHtml = sb.toString();
            }

            // 동아리별 일정 HTML 구성
            try (PreparedStatement stmt = conn.prepareStatement(clubEventQuery)) {
                ResultSet rs = stmt.executeQuery();
                String currentClub = "";
                StringBuilder sb = new StringBuilder();

                while (rs.next()) {
                    String clubName = rs.getString("name");
                    // 새 동아리 이름이 나오면 이전 블록 닫고 새 블록 시작
                    if (!clubName.equals(currentClub)) {
                        if (!currentClub.isEmpty()) {
                            sb.append("</div>");
                        }
                        currentClub = clubName;
                        sb.append("<h2>").append(clubName).append("</h2><div class=\"schedule-group\">");
                    }

                    // 개별 일정 항목 HTML 구성
                    sb.append("<div class=\"schedule-item\">")
                            .append("<div class=\"item-left\">")
                            .append("<span class=\"item-title\">").append(rs.getString("title")).append("</span>")
                            .append("<span class=\"item-date\">").append(rs.getString("date")).append("</span>")
                            .append("<div class=\"item-content\">").append(rs.getString("content")).append("</div>")
                            .append("<span class=\"event-id\" style=\"display:none;\">").append(rs.getInt("id")).append("</span>")
                            .append("</div>")
                            .append("<span class=\"item-actions\">")
                            .append("<button class=\"edit-btn\">✎</button>")
                            .append("<button class=\"delete-btn\">🗑</button>")
                            .append("</span>")
                            .append("</div>");
                }

                if (!currentClub.isEmpty()) {
                    sb.append("</div>");
                }

                clubHtml = sb.toString();
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        // HTML 템플릿에 데이터 삽입
        html = html.replace("{{subMajors}}", subMajors != null ? subMajors : "없음")
                .replace("{{clubs}}", clubs != null ? clubs : "없음")
                .replace("{{departmentGroups}}", departmentHtml)
                .replace("{{clubGroups}}", clubHtml);

        // 최종 HTML 클라이언트로 전송
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(html.getBytes());
        os.close();
    }

    // 그룹 목록(학과, 동아리)을 반환하는 메서드 (AJAX 요청 등에 사용)
    private static void handleScheduleGroups(HttpExchange exchange) throws IOException {
        // GET 요청만 허용
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            return;
        }

        StringBuilder html = new StringBuilder();

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            // 학과 그룹 목록 HTML 생성
            html.append("<div class='group-section'><h2>학과</h2>");
            String deptQuery = "SELECT name FROM DB2025_DEPARTMENT";
            try (PreparedStatement stmt = conn.prepareStatement(deptQuery); ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    html.append("<div class='group-item' data-type='department' data-group='").append(name).append("'>")
                            .append(name).append("</div>");
                }
            }
            html.append("</div>");

            // 동아리 그룹 목록 HTML 생성
            html.append("<div class='group-section'><h2>동아리</h2>");
            String clubQuery = "SELECT name FROM DB2025_CLUB";
            try (PreparedStatement stmt = conn.prepareStatement(clubQuery); ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    html.append("<div class='group-item' data-type='club' data-group='").append(name).append("'>")
                            .append(name).append("</div>");
                }
            }
            html.append("</div>");

            // 결과 전송
            byte[] response = html.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        } catch (SQLException e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1); // 서버 오류
        }
    }

    // 일정 추가 요청 처리 핸들러
    private static void handleAddEvent(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1); // 허용되지 않은 메서드
            return;
        }

        // 요청 본문 파싱
        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(isr);
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        String formData = sb.toString();
        Map<String, String> data = parseFormData(formData);

        // 폼 데이터에서 값 추출
        String type = data.get("type");
        String groupName = data.get("group");
        String title = data.get("title");
        String datetime = data.get("date");
        String content = data.get("content");

        System.out.println("group: " + groupName);
        System.out.println("title: " + title);
        System.out.println("content: " + content);
        System.out.println("startDate: " + datetime);

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            // 학과/동아리 이름으로 ref_id 조회
            String refQuery = type.equals("department") ? "SELECT id FROM DB2025_DEPARTMENT WHERE name = ?"
                    : "SELECT id FROM DB2025_CLUB WHERE name = ?";

            int refId = -1;
            try (PreparedStatement stmt = conn.prepareStatement(refQuery)) {
                stmt.setString(1, groupName);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    refId = rs.getInt("id");
                } else {
                    exchange.sendResponseHeaders(400, -1); // 그룹이 존재하지 않음
                    return;
                }
            }

            // 일정 데이터 INSERT
            String insertQuery = "INSERT INTO DB2025_EVENT (type, ref_id, title, date, content) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertQuery)) {
                stmt.setString(1, type);
                stmt.setInt(2, refId);
                stmt.setString(3, title);
                stmt.setString(4, datetime);
                stmt.setString(5, content);
                stmt.executeUpdate();
            }

            exchange.sendResponseHeaders(200, -1); // 성공
        } catch (SQLException e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1); // 서버 오류
        }
    }

    // x-www-form-urlencoded 데이터 파싱 유틸 함수
    private static Map<String, String> parseFormData(String formData) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (formData == null || formData.isEmpty()) {
            return map;
        }

        String[] pairs = formData.split("&");
        for (String pair : pairs) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                String key = URLDecoder.decode(parts[0], "UTF-8");
                String value = URLDecoder.decode(parts[1], "UTF-8");
                map.put(key, value);
            }
        }
        return map;
    }

    // 일정 삭제 요청 처리 핸들러
    private static void handleDeleteEvent(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); // 허용되지 않은 메서드
            return;
        }

        // 요청 본문 읽기 및 id 추출
        BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
        StringBuilder buf = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            buf.append(line);
        }
        String requestBody = buf.toString();

        Map<String, String> params = queryToMap(requestBody);
        String idStr = params.get("id");

        if (idStr == null) {
            String response = "Missing id parameter";
            exchange.sendResponseHeaders(400, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        int eventId;
        try {
            eventId = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            String response = "Invalid id parameter";
            exchange.sendResponseHeaders(400, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        // DB에서 일정 삭제
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            String deleteSql = "DELETE FROM DB2025_EVENT WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                stmt.setInt(1, eventId);
                int affectedRows = stmt.executeUpdate();
                if (affectedRows > 0) {
                    String response = "Deleted";
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    String response = "Event not found";
                    exchange.sendResponseHeaders(404, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            String response = "DB error";
            exchange.sendResponseHeaders(500, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    // 쿼리 문자열을 Map으로 파싱하는 함수
    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    result.put(key, value);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return result;
    }

    // 사용자의 관심 학과/동아리 일정 조회 처리 핸들러
    private static void handleScheduleUser(HttpExchange exchange) throws IOException {
        String userId = getUserIdFromCookie(exchange);
        if (userId == null) {
            // 로그인 안 된 경우 로그인 페이지로 리디렉션
            exchange.getResponseHeaders().add("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        StringBuilder html = new StringBuilder();

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            // 사용자 관련 학과/동아리 일정 조회 쿼리
            String sql = """ 
                    SELECT 
                        e.id AS event_id,
                        e.title,
                        e.date,
                        e.type,
                        CASE 
                            WHEN e.type = 'department' THEN d.name
                            WHEN e.type = 'club' THEN c.name
                            ELSE '기타'
                        END AS group_name,
                        CASE 
                            WHEN l.user_id IS NOT NULL THEN true
                            ELSE false
                        END AS liked
                    FROM DB2025_EVENT e
                    LEFT JOIN DB2025_DEPARTMENT d ON e.type = 'department' AND e.ref_id = d.id
                    LEFT JOIN DB2025_CLUB c ON e.type = 'club' AND e.ref_id = c.id
                    LEFT JOIN db2025_liked_events_view l ON e.id = l.event_id AND l.user_id = ?
                    WHERE EXISTS (
                        SELECT 1 FROM DB2025_USER_DEPARTMENT ud 
                        WHERE ud.user_id = ? AND ud.department_id = e.ref_id AND e.type = 'department'
                    )
                    OR EXISTS (
                        SELECT 1 FROM DB2025_USER_CLUB uc 
                        WHERE uc.user_id = ? AND uc.club_id = e.ref_id AND e.type = 'club'
                    )
                    ORDER BY group_name, e.date
                """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                stmt.setString(2, userId);
                stmt.setString(3, userId);

                ResultSet rs = stmt.executeQuery();
                Map<String, List<String>> groupedEvents = new LinkedHashMap<>();

                while (rs.next()) {
                    String group = rs.getString("group_name");
                    String title = rs.getString("title");
                    String date = rs.getString("date");
                    int eventId = rs.getInt("event_id");
                    boolean liked = rs.getBoolean("liked");

                    String heart = liked ? "❤️" : "♡";

                    String item = "<div class='schedule-item'>" +
                            "<div class='item-left'>" +
                            "<span class='item-title bold'>" + title + "</span>" +
                            "<span class='item-date'>" + date + "</span>" +
                            "</div>" +
                            "<span class='item-actions'>" +
                            "<button class='heart-btn' data-liked='" + liked + "' data-event-id='" + eventId + "'>" +
                            heart + "</button>" +
                            "</span></div>";

                    groupedEvents.computeIfAbsent(group, k -> new ArrayList<>()).add(item);
                }

                // 그룹별로 HTML 조합
                for (Map.Entry<String, List<String>> entry : groupedEvents.entrySet()) {
                    html.append("<div class='schedule-group'>")
                            .append("<div class='group-title'>").append(entry.getKey()).append(" 일정</div>");
                    for (String item : entry.getValue()) {
                        html.append(item);
                    }
                    html.append("</div>");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            html.append("<p>일정 정보를 불러오는 중 오류가 발생했습니다.</p>");
        }

        byte[] response = html.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    // 일정 수정 요청 처리 핸들러
    private static void handleEditEvent(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); // 허용되지 않은 메서드
            return;
        }

        // 요청 본문 파싱
        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .lines().reduce("", (acc, line) -> acc + line);

        Map<String, String> params = parseQuery(body);

        String idStr = params.get("id");
        String newTitle = params.get("newTitle");
        String newDate = params.get("newDate");
        String newContent = params.get("newContent");

        if (idStr == null || newTitle == null || newDate == null) {
            exchange.sendResponseHeaders(400, -1); // 필수 값 누락
            return;
        }

        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            exchange.sendResponseHeaders(400, -1); // id가 정수가 아님
            return;
        }

        // 일정 정보 업데이트 쿼리 실행
        String sql = "UPDATE DB2025_EVENT SET title = ?, date = ?, content = ? WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, newTitle);
            stmt.setString(2, newDate);
            stmt.setString(3, newContent);
            stmt.setInt(4, id);

            int affected = stmt.executeUpdate();

            if (affected > 0) {
                exchange.sendResponseHeaders(200, -1); // 성공
            } else {
                exchange.sendResponseHeaders(404, -1); // 해당 ID 없음
            }
        } catch (SQLException e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1); // DB 오류
        }
    }
}
