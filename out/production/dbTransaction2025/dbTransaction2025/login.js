document.getElementById("login-form").addEventListener("submit", async function (e) {
    e.preventDefault();

    const id = document.getElementById("username").value;
    const pw = document.getElementById("password").value;

    try {
        const response = await fetch("http://localhost:8080/login", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded",
            },
            credentials: "include",
            body: formData.toString()
        });

        // const response = await fetch("/login", {
        //     method: "POST",
        //     headers: {
        //         "Content-Type": "application/x-www-form-urlencoded",
        //     },
        //     body: new URLSearchParams({
        //         id,
        //         password: pw
        //     }).toString(),
        // });

        const result = await response.json();
        if (result.success === true) {
            window.location.href = "home";
        } else {
            alert("아이디 또는 비밀번호가 틀렸습니다.");
        }
    } catch (error) {
        console.error("로그인 요청 실패:", error);
        alert("서버 오류가 발생했습니다.");
    }
});
