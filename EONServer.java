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
import java.util.*;

public class EONServer {

    static final String DB_URL = "jdbc:mysql://localhost/DB2025Team06";
    static final String USER = "DB2025Team06";
    static final String PASS = "anna0715";

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


        server.setExecutor(null);
        server.start();
        System.out.println("서버 실행 중: http://localhost:8080");
    }

    private static void serveStaticFile(HttpExchange exchange, String fileName, String contentType) throws IOException {
        byte[] bytes = Files.readAllBytes(new File(fileName).toPath());
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String getUserIdFromCookie(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies != null) {
            for (String cookie : cookies) {
                for (String pair : cookie.split(";")) {
                    String[] kv = pair.trim().split("=");
                    if (kv.length == 2 && kv[0].equals("sessionId")) {
                        return sessionMap.get(kv[1]);
                    }
                }
            }
        }
        return null;
    }

    private static void handleHome(HttpExchange exchange) throws IOException {
        String userId = getUserIdFromCookie(exchange);
        if (userId == null) {
            exchange.getResponseHeaders().add("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        String html = new String(Files.readAllBytes(new File("home.html").toPath()));
        StringBuilder ddayItems = new StringBuilder();
        StringBuilder calendarTable = new StringBuilder();

        String role = getUserRoleFromCookie(exchange);
        if ("admin".equals(role)) {
            exchange.getResponseHeaders().add("Location", "/scheduleadmin");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            Map<Integer, List<String>> eventsByDay = new HashMap<>();
            String eventQuery = """
                        SELECT DAY(date) AS day, title, type
                        FROM DB2025_EVENT e
                        JOIN DB2025_EVENT_LIKE l ON e.id = l.event_id
                        WHERE l.user_id = ?
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

            String ddayQuery = """
                        SELECT e.title, DATEDIFF(e.date, CURRENT_DATE) AS d_day, e.type
                        FROM DB2025_EVENT e
                        JOIN DB2025_EVENT_LIKE l ON e.id = l.event_id
                        WHERE l.user_id = ?
                          AND e.date >= CURRENT_DATE
                        ORDER BY e.date
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

            LocalDate today = LocalDate.now();
            int year = today.getYear(), month = today.getMonthValue(), todayDay = today.getDayOfMonth();
            LocalDate firstDay = LocalDate.of(year, month, 1);
            int startDay = firstDay.getDayOfWeek().getValue();
            int totalDays = firstDay.lengthOfMonth();

            int cellCount = 0;
            calendarTable.append("<tr>");

            String calendarTitle = today.getMonth().toString().substring(0, 1).toUpperCase() +
                    today.getMonth().toString().substring(1).toLowerCase() + " " + year;
            html = html.replace("{{calendarTitle}}", calendarTitle);

            for (int i = 1; i < startDay; i++, cellCount++) {
                calendarTable.append("<td class='empty'></td>");
            }

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
            while (cellCount % 7 != 0) {
                calendarTable.append("<td class='empty'></td>");
                cellCount++;
            }
            calendarTable.append("</tr>");

        } catch (SQLException e) {
            e.printStackTrace();
        }

        html = html.replace("{{calendarTable}}", calendarTable.toString())
                .replace("{{ddayList}}", ddayItems.toString());

        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html.getBytes());
        }
    }

    private static void handleMypage(HttpExchange exchange) throws IOException {
        String id = getUserIdFromCookie(exchange);
        if (id == null) {
            exchange.getResponseHeaders().add("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        String html = new String(Files.readAllBytes(new File("mypage.html").toPath()));

        String name = "", major = "", subMajors = "", clubs = "";

        String query = """
                    SELECT u.name, u.id,
                           CONCAT_WS(' ', d_major.name, d_major.college_name) AS major,
                           (SELECT GROUP_CONCAT(CONCAT_WS(' ', d.name, d.college_name) SEPARATOR ', ')
                            FROM DB2025_USER_DEPARTMENT ud
                            JOIN DB2025_DEPARTMENT d ON ud.department_id = d.id
                            WHERE ud.user_id = u.id AND ud.major_type = 'minor') AS sub_majors,
                           (SELECT GROUP_CONCAT(c.name SEPARATOR ', ')
                            FROM DB2025_USER_CLUB uc
                            JOIN DB2025_CLUB c ON uc.club_id = c.id
                            WHERE uc.user_id = u.id) AS clubs
                    FROM DB2025_USER u
                    LEFT JOIN DB2025_USER_DEPARTMENT ud_major
                      ON u.id = ud_major.user_id AND ud_major.major_type = 'major'
                    LEFT JOIN DB2025_DEPARTMENT d_major
                      ON ud_major.department_id = d_major.id
                    WHERE u.id = ?
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

        html = html.replace("{{name}}", name)
                .replace("{{id}}", id)
                .replace("{{major}}", major != null ? major : "없음")
                .replace("{{subMajors}}", subMajors != null ? subMajors : "없음")
                .replace("{{clubs}}", clubs != null ? clubs : "없음");

        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html.getBytes());
        }
    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(isr);
        String formData = reader.readLine();
        if (formData == null || formData.isBlank()) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        Map<String, String> params = new HashMap<>();
        for (String pair : formData.split("&")) {
            String[] parts = pair.split("=");
            if (parts.length >= 2) {
                params.put(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8"));
            }
        }

        String id = params.get("id");
        String pw = params.get("password");

        String sql = "SELECT password, role FROM DB2025_USER WHERE id = ?";

        String role = null;
        boolean success = false;
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                success = rs.getString("password").equals(pw);
                role = rs.getString("role"); // ✅ role은 DB에서 받아옴
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (success) {
            String sessionId = UUID.randomUUID().toString();
            sessionMap.put(sessionId, id);
            sessionRoleMap.put(sessionId, role); // ★ 역할 저장
            exchange.getResponseHeaders().add("Set-Cookie", "sessionId=" + sessionId + "; Path=/");
            exchange.getResponseHeaders().add("Location", "/home");
            exchange.sendResponseHeaders(302, -1); // 리디렉션
        } else {
            exchange.getResponseHeaders().add("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1); // 로그인 실패 시 다시 로그인 페이지로
        }
        exchange.close();
    }

    private static void handleSignup(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(isr);
        String formData = reader.readLine();

        Map<String, List<String>> params = new HashMap<>();
        for (String pair : formData.split("&")) {
            String[] parts = pair.split("=");
            String key = URLDecoder.decode(parts[0], "UTF-8");
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], "UTF-8") : "";
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }

        long id = Long.parseLong(params.get("id").get(0));
        String name = params.get("name").get(0);
        String password = params.get("password").get(0);
        long majorId = mapMajor(params.get("major").get(0));
        List<Long> minors = mapAll(params.getOrDefault("minors[]", List.of()));
        List<Long> clubs = mapAll(params.getOrDefault("clubs[]", List.of()));

        boolean success = insertUserAll(id, name, password, majorId, minors, clubs);

        String res = success ? "{\"success\":true}" : "{\"success\":false}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, res.getBytes().length);
        exchange.getResponseBody().write(res.getBytes());
        exchange.close();
    }

    private static List<Long> mapAll(List<String> names) {
        List<Long> list = new ArrayList<>();
        for (String n : names) {
            long id = n.matches("\\d+") ? Long.parseLong(n) : mapMajor(n);
            if (id > 0) {
                list.add(id);
            }
        }
        return list;
    }

    private static long mapMajor(String name) {
        return switch (name) {
            case "컴퓨터공학과" -> 1;
            case "사이버보안학과" -> 2;
            case "인공지능전공" -> 3;
            case "데이터사이언스전공" -> 4;
            default -> -1;
        };
    }

    private static boolean insertUserAll(long id, String name, String password, long majorId, List<Long> minors,
                                         List<Long> clubs) {
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            conn.setAutoCommit(false);

            if (checkUserExists(conn, id)) {
                return false;
            }
            insertUser(conn, id, name, password);  // ← 여기 비밀번호 추가됨
            insertDepartment(conn, id, majorId, "major", "ud_major");

            for (int i = 0; i < minors.size(); i++) {
                insertDepartment(conn, id, minors.get(i), "minor", "ud_minor_" + i);
            }

            for (int i = 0; i < clubs.size(); i++) {
                insertClub(conn, id, clubs.get(i), "uc_" + i);
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean checkUserExists(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM DB2025_USER WHERE id = ?")) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private static void insertUser(Connection conn, long id, String name, String password) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO DB2025_USER (id, name, password, role) VALUES (?, ?, ?, 'user')")) {
            ps.setLong(1, id);
            ps.setString(2, name);
            ps.setString(3, password);
            ps.executeUpdate();
        }
    }

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

    private static void insertClub(Connection conn, long userId, long clubId, String genId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO DB2025_USER_CLUB (id, user_id, club_id) VALUES (?, ?, ?)")) {
            ps.setString(1, genId);
            ps.setLong(2, userId);
            ps.setLong(3, clubId);
            ps.executeUpdate();
        }
    }

    private static void handleChangePassword(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .lines().reduce("", (acc, line) -> acc + line);
        Map<String, String> params = parseQuery(body);

        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");
        String userId = params.get("userId");

        if (oldPassword == null || newPassword == null || userId == null) {
            exchange.sendResponseHeaders(400, -1);
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
                    // 기존 비밀번호 불일치
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }

                // 비밀번호 변경
                try (PreparedStatement stmtUpdate = conn.prepareStatement(queryUpdate)) {
                    stmtUpdate.setString(1, newPassword);
                    stmtUpdate.setString(2, userId);
                    int updated = stmtUpdate.executeUpdate();

                    if (updated > 0) {
                        exchange.sendResponseHeaders(200, -1);
                    } else {
                        exchange.sendResponseHeaders(500, -1);
                    }
                }
            } else {
                // 사용자 없음
                exchange.sendResponseHeaders(404, -1);
            }

        } catch (SQLException e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
        }
    }

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

    private static void handleDeleteUser(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String loggedInUserId = getUserIdFromCookie(exchange); // 현재 로그인된 사용자 ID 가져오기
        if (loggedInUserId == null) {
            exchange.getResponseHeaders().add("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .lines().reduce("", (acc, line) -> acc + line);

        Map<String, String> params = parseQuery(body);
        String userIdToDelete = params.get("userId"); // 요청 본문에서 탈퇴할 사용자 ID 가져오기

        if (userIdToDelete == null) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        // 로그인된 사용자와 탈퇴 요청 사용자 ID가 일치하는지 확인
        if (!loggedInUserId.equals(userIdToDelete)) {
            exchange.sendResponseHeaders(403, -1); // 권한 없음
            return;
        }

        String query = "DELETE FROM DB2025_USER WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, userIdToDelete); // 일치하는 사용자 ID 사용
            int deleted = stmt.executeUpdate();

            if (deleted > 0) {
                // 회원 탈퇴 성공 시 세션도 만료
                exchange.getResponseHeaders()
                        .add("Set-Cookie", "sessionId=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                sessionMap.remove(loggedInUserId); // 세션 맵에서도 제거
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
        }
    }

    private static void handleLogout(HttpExchange exchange) throws IOException {
        // 클라이언트에게 sessionId 쿠키를 삭제하도록 지시
        exchange.getResponseHeaders().add("Set-Cookie", "sessionId=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT");

        // 세션 맵에서 해당 세션 ID 제거 (선택 사항이지만 보안 강화에 도움)
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
                        "<button class='heart-btn'>♡</button>" + // liked 컬럼 없으므로 기본 하트 표시
                        "</span></div>";

                groupMap.computeIfAbsent(group, k -> new ArrayList<>()).add(itemHtml);
            }

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

        // {events} 치환
        html = html.replace("{events}", eventHtml.toString());

        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(html.getBytes());
        os.close();
    }


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

    private static void handleScheduleAdmin(HttpExchange exchange) throws IOException {
        String role = getUserRoleFromCookie(exchange);
        if (!"admin".equals(role)) {
            exchange.sendResponseHeaders(403, -1); // 접근 금지
            return;
        }

        String html = new String(Files.readAllBytes(new File("schedule_admin.html").toPath()));
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html.getBytes());
        }
    }


    private static void handleSchedule(HttpExchange exchange) throws IOException {
        String id = "101"; // 실제에선 GET 쿼리 파라미터 처리 가능
        System.out.println("Working dir: " + new File(".").getAbsolutePath());
        String html = new String(Files.readAllBytes(new File("schedule_admin.html").toPath()));
        String name = "", major = "", subMajors = "", clubs = "";
        String departmentHtml = "", clubHtml = "";

        String userQuery = "SELECT u.name, u.id, " + "CONCAT_WS(' ', d_major.name, d_major.college_name) AS major, "
                + "(SELECT GROUP_CONCAT(CONCAT_WS(' ', d.name, d.college_name) SEPARATOR ', ') "
                + " FROM DB2025_USER_DEPARTMENT ud JOIN DB2025_DEPARTMENT d ON ud.department_id = d.id "
                + " WHERE ud.user_id = u.id AND ud.major_type = 'minor') AS sub_majors, "
                + "(SELECT GROUP_CONCAT(c.name SEPARATOR ', ') FROM DB2025_USER_CLUB uc "
                + " JOIN DB2025_CLUB c ON uc.club_id = c.id WHERE uc.user_id = u.id) AS clubs " + "FROM DB2025_USER u "
                + "LEFT JOIN DB2025_USER_DEPARTMENT ud_major ON u.id = ud_major.user_id AND ud_major.major_type = 'major' "
                + "LEFT JOIN DB2025_DEPARTMENT d_major ON ud_major.department_id = d_major.id " + "WHERE u.id = ?";

        String departmentEventQuery = "SELECT e.id, d.name, d.college_name, e.title, e.date, e.content "
                + "FROM DB2025_EVENT e " + "JOIN DB2025_DEPARTMENT d ON e.ref_id = d.id "
                + "WHERE e.type = 'department' " + "ORDER BY d.name, e.date";

        String clubEventQuery = "SELECT e.id, c.name, e.title, e.date, e.content " + "FROM DB2025_EVENT e "
                + "JOIN DB2025_CLUB c ON e.ref_id = c.id " + "WHERE e.type = 'club' " + "ORDER BY c.name, e.date";

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            // 사용자 정보
            try (PreparedStatement stmt = conn.prepareStatement(userQuery)) {
                stmt.setString(1, id);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    name = rs.getString("name");
                    major = rs.getString("major");
                    subMajors = rs.getString("sub_majors");
                    clubs = rs.getString("clubs");
                }
            }

            // 학과별 이벤트 HTML 구성
            try (PreparedStatement stmt = conn.prepareStatement(departmentEventQuery)) {
                ResultSet rs = stmt.executeQuery();
                String currentDepartment = "";
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    String deptName = rs.getString("name") + " (" + rs.getString("college_name") + ")";
                    if (!deptName.equals(currentDepartment)) {
                        if (!currentDepartment.isEmpty())
                            sb.append("</div>"); // 이전 블록 닫기
                        currentDepartment = deptName;
                        sb.append("<h2>").append(deptName).append("</h2><div class=\"schedule-group\">");
                    }
                    sb.append("<div class=\"schedule-item\">").append("<div class=\"item-left\">")
                            .append("<span class=\"item-title\">").append(rs.getString("title")).append("</span>")
                            .append("<span class=\"item-date\">").append(rs.getString("date")).append("</span>")
                            .append("<div class=\"item-content\">").append(rs.getString("content")).append("</div>")
                            .append("<span class=\"event-id\" style=\"display:none;\">").append(rs.getInt("id"))
                            .append("</span>").append("</div>").append("<span class=\"item-actions\">")
                            .append("<button class=\"edit-btn\">✎</button>")
                            .append("<button class=\"delete-btn\">🗑</button>").append("</span>").append("</div>");
                }
                if (!currentDepartment.isEmpty())
                    sb.append("</div>");
                departmentHtml = sb.toString();
            }

            // 동아리별 이벤트 HTML 구성
            try (PreparedStatement stmt = conn.prepareStatement(clubEventQuery)) {
                ResultSet rs = stmt.executeQuery();
                String currentClub = "";
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    String clubName = rs.getString("name");
                    if (!clubName.equals(currentClub)) {
                        if (!currentClub.isEmpty())
                            sb.append("</div>");
                        currentClub = clubName;
                        sb.append("<h2>").append(clubName).append("</h2><div class=\"schedule-group\">");
                    }
                    sb.append("<div class=\"schedule-item\">").append("<div class=\"item-left\">")
                            .append("<span class=\"item-title\">").append(rs.getString("title")).append("</span>")
                            .append("<span class=\"item-date\">").append(rs.getString("date")).append("</span>")
                            .append("<div class=\"item-content\">").append(rs.getString("content")).append("</div>")
                            .append("<span class=\"event-id\" style=\"display:none;\">").append(rs.getInt("id"))
                            .append("</span>").append("</div>").append("<span class=\"item-actions\">")
                            .append("<button class=\"edit-btn\">✎</button>")
                            .append("<button class=\"delete-btn\">🗑</button>").append("</span>").append("</div>");
                }
                if (!currentClub.isEmpty())
                    sb.append("</div>");
                clubHtml = sb.toString();
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        // HTML 템플릿에서 치환
        html = html.replace("{{name}}", name).replace("{{id}}", id).replace("{{major}}", major != null ? major : "없음")
                .replace("{{subMajors}}", subMajors != null ? subMajors : "없음")
                .replace("{{clubs}}", clubs != null ? clubs : "없음").replace("{{departmentGroups}}", departmentHtml)
                .replace("{{clubGroups}}", clubHtml);

        // 응답
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(html.getBytes());
        os.close();
    }

    private static void handleScheduleGroups(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        StringBuilder html = new StringBuilder();

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            // 학과 그룹
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

            // 동아리 그룹
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

            byte[] response = html.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        } catch (SQLException e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
        }
    }

    private static void handleAddEvent(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
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

        String type = data.get("type"); // "department" 또는 "club"
        String groupName = data.get("group");
        String title = data.get("title");
        String datetime = data.get("date");
        String content = data.get("content");

        System.out.println("group: " + groupName);
        System.out.println("title: " + title);
        System.out.println("content: " + content);
        System.out.println("startDate: " + datetime);

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            String refQuery = type.equals("department") ? "SELECT id FROM DB2025_DEPARTMENT WHERE name = ?"
                    : "SELECT id FROM DB2025_CLUB WHERE name = ?";

            int refId = -1;
            try (PreparedStatement stmt = conn.prepareStatement(refQuery)) {
                stmt.setString(1, groupName);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    refId = rs.getInt("id");
                } else {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
            }

            String insertQuery = "INSERT INTO DB2025_EVENT (type, ref_id, title, date, content) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertQuery)) {
                stmt.setString(1, type);
                stmt.setInt(2, refId);
                stmt.setString(3, title);
                stmt.setString(4, datetime);
                stmt.setString(5, content);
                stmt.executeUpdate();
            }

            exchange.sendResponseHeaders(200, -1);
        } catch (SQLException e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
        }
    }

    private static Map<String, String> parseFormData(String formData) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (formData == null || formData.isEmpty())
            return map;

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

    private static void handleDeleteEvent(HttpExchange exchange) throws IOException {
        System.out.println("ddd");
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            return;
        }
        System.out.println("ddd");

        InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        StringBuilder buf = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            buf.append(line);
        }
        String requestBody = buf.toString();

        // id 파라미터 추출 (예: id=123)
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
        System.out.println("ddd");

        // DB 연결 및 삭제 쿼리 실행
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

        System.out.println("ddd");
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty())
            return result;
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

    private static void handleEditEvent(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .lines().reduce("", (acc, line) -> acc + line);

        Map<String, String> params = parseQuery(body);

        String idStr = params.get("id");
        String newTitle = params.get("newTitle");
        String newDate = params.get("newDate");
        String newContent = params.get("newContent");

        if (idStr == null || newTitle == null || newDate == null) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        String sql = "UPDATE DB2025_EVENT SET title = ?, date = ?, content = ? WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, newTitle);
            stmt.setString(2, newDate);
            stmt.setString(3, newContent);
            stmt.setInt(4, id);

            int affected = stmt.executeUpdate();

            if (affected > 0) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
        }
    }

}
