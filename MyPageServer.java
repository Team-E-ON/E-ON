package dbTransaction2025;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MyPageServer {

//    static final String DB_URL = "jdbc:mysql://10.240.121.32/db2025_eon";
//    static final String USER = "dbauser";
//    static final String PASS = "dbauser123!";

    static final String DB_URL = "jdbc:mysql://localhost/db2025_eon";
    static final String USER = "root";
    static final String PASS = "rina030429";

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/mypage", MyPageServer::handleMypage);
        server.createContext("/mypage.css", exchange -> { // 정적 CSS 서빙
            byte[] bytes = Files.readAllBytes(new File("mypage.css").toPath());
            exchange.getResponseHeaders().add("Content-Type", "text/css");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(null);
        server.start();
        System.out.println("서버 실행 중: http://localhost:8080/mypage");
    }

    private static void handleMypage(HttpExchange exchange) throws IOException {
        String id = "101";  // 실제에선 GET 쿼리 파라미터 처리 가능
        String html = new String(Files.readAllBytes(new File("mypage.html").toPath()));

        String name = "", major = "", subMajors = "", clubs = "";

        String query = "SELECT u.name, u.id, "
                + "CONCAT_WS(' ', d_major.name, d_major.college_name) AS major, "
                + "(SELECT GROUP_CONCAT(CONCAT_WS(' ', d.name, d.college_name) SEPARATOR ', ') "
                + " FROM DB2025_USER_DEPARTMENT ud "
                + " JOIN DB2025_DEPARTMENT d ON ud.department_id = d.id "
                + " WHERE ud.user_id = u.id AND ud.major_type = 'minor') AS sub_majors, "
                + "(SELECT GROUP_CONCAT(c.name SEPARATOR ', ') "
                + " FROM DB2025_USER_CLUB uc "
                + " JOIN DB2025_CLUB c ON uc.club_id = c.id "
                + " WHERE uc.user_id = u.id) AS clubs "
                + "FROM DB2025_USER u "
                + "LEFT JOIN DB2025_USER_DEPARTMENT ud_major "
                + " ON u.id = ud_major.user_id AND ud_major.major_type = 'major' "
                + "LEFT JOIN DB2025_DEPARTMENT d_major "
                + " ON ud_major.department_id = d_major.id "
                + "WHERE u.id = ?";

        try (
                Connection conn = DriverManager.getConnection(DB_URL, USER, PASS);
                PreparedStatement stmt = conn.prepareStatement(query)
        ) {
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

        // HTML 템플릿에서 치환
        html = html.replace("{{name}}", name)
                .replace("{{id}}", id)
                .replace("{{major}}", major != null ? major : "없음")
                .replace("{{subMajors}}", subMajors != null ? subMajors : "없음")
                .replace("{{clubs}}", clubs != null ? clubs : "없음");

        // 응답
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(html.getBytes());
        os.close();
    }
}
