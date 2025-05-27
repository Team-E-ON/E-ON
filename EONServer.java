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
import java.util.List;
import java.util.Map;
import java.util.UUID;


public class EONServer {

    static final String DB_URL = "jdbc:mysql://localhost/DB2025Team06";
    static final String USER = "DB2025Team06";
    static final String PASS = "DB2025Team06";

    // ★ 세션 ID → 사용자 이름 매핑
    private static final Map<String, String> sessionMap = new HashMap<>();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "/home");
            exchange.sendResponseHeaders(302, -1);
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
        server.createContext("/Home.js", ex -> serveStaticFile(ex, "Home.js", "application/javascript"));
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

    // ★ 사용자 이름을 쿠키에서 가져오기
    private static String getUserNameFromCookie(HttpExchange exchange) {
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
        String userName = getUserNameFromCookie(exchange); // ★ 이름으로 바꿈
        if (userName == null) {
            exchange.getResponseHeaders().add("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        String html = new String(Files.readAllBytes(new File("home.html").toPath()));
        StringBuilder ddayItems = new StringBuilder();
        StringBuilder calendarTable = new StringBuilder();

        // ★ 사용자 ID를 얻기 위해 이름 → ID 변환
        String userId = null;
        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement stmt = conn.prepareStatement("SELECT id FROM DB2025_USER WHERE name = ?")) {
            stmt.setString(1, userName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                userId = rs.getString("id");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (userId == null) {
            exchange.sendResponseHeaders(500, -1);
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

        // ★ username 쿠키로 클라이언트에 전달
        html += "<script>document.cookie = 'username=" + userName + "';</script>";

        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html.getBytes());
        }
    }

    private static void handleMypage(HttpExchange exchange) throws IOException {
        String id = getUserNameFromCookie(exchange);
        if (id == null) {
            exchange.getResponseHeaders().add("Location", "/login.html");
            exchange.sendResponseHeaders(302, -1);
            return;
        }

        String html = new String(Files.readAllBytes(new File("mypage.html").toPath()));

        String name = "", major = "", subMajors = "", clubs = "";

        String query = """
                SELECT * FROM DB2025_mypage_user_info_view
                                    WHERE user_id = ?
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
        // ✅ CORS Preflight 요청 처리
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1); // No Content
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            return;
        }

        // ✅ CORS 응답 헤더 (실제 응답에도 꼭 넣어야 함)
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Content-Type", "application/json");

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
        String sql = "SELECT name, password FROM DB2025_USER WHERE id = ?";
        String name = null;
        boolean success = false;

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                success = rs.getString("password").equals(pw);
                if (success) {
                    name = rs.getString("name");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (success) {
            String sessionId = UUID.randomUUID().toString();
            sessionMap.put(sessionId, name);
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

    private static String getUserIdFromCookie(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if(cookies != null){
            for(String cookie : cookies){
                for(String pair : cookie.split(";")){
                    String[] kv = pair.trim().split("=");
                    if(kv.length == 2 && kv[0].equals("sessionId")){
                        return sessionMap.get(kv[1]);
                    }
                }
            }
        }
        return null;
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
}
