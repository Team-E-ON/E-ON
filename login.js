document.getElementById("login-form").addEventListener("submit", async function (e) {
    e.preventDefault();

    const id = document.getElementById("username").value.trim();
    const pw = document.getElementById("password").value.trim();

    const formData = new URLSearchParams();
    formData.append("id", id);
    formData.append("password", pw);

    try {
      const res = await fetch("http://localhost:8080/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: formData.toString()
      });

      const data = await res.json();
      if (data.success === true) {
        alert("로그인 성공!");
        window.location.href = "home.html";
      } else {
        alert("아이디 또는 비밀번호가 틀렸습니다.");
      }
    } catch (err) {
      console.error(err);
      alert("서버 오류로 로그인에 실패했습니다.");
    }
  });