document.addEventListener("DOMContentLoaded", () => {
    const form = document.querySelector(".signup-form");
    const studentIdInput = form.querySelector("input[placeholder*='학번']");
    const nameInput = form.querySelector("input[placeholder*='이름']");
    const majorSelect = form.querySelector("select");
  
    // 복수전공/동아리 선택 버튼 토글
    document.querySelectorAll(".select-btn").forEach(btn => {
      btn.addEventListener("click", () => {
        btn.classList.toggle("selected");
      });
    });
  
    // 중복 체크 버튼 (데모 alert)
    const doubleCheckBtn = document.getElementById("doubleCheck");
    doubleCheckBtn.addEventListener("change", () => {
      if (doubleCheckBtn.checked) {
        alert("중복 체크 완료 (데모)");
      }
    });
  
    // 폼 제출 처리
    form.addEventListener("submit", (e) => {
      e.preventDefault(); 
  
      const studentId = studentIdInput.value.trim();
      const name = nameInput.value.trim();
      const major = majorSelect.value;
  
      const subMajors = [...form.querySelectorAll(".form-group:nth-of-type(4) .select-btn.selected")]
        .map(btn => btn.textContent);
      const clubs = [...form.querySelectorAll(".form-group:nth-of-type(5) .select-btn.selected")]
        .map(btn => btn.textContent);
  
      // 유효성 검사
      if (!/^[0-9]{7}$/.test(studentId)) {
        alert("학번은 7자리 숫자여야 합니다.");
        return;
      }
  
      if (name === "") {
        alert("이름을 입력해주세요.");
        return;
      }
  
      if (major === "선택") {
        alert("주전공을 선택해주세요.");
        return;
      }
  
      // 결과 확인 (개발용)
      console.log("학번:", studentId);
      console.log("이름:", name);
      console.log("주전공:", major);
      console.log("복수/부전공:", subMajors);
      console.log("동아리:", clubs);
  
    
            // POST용 데이터 구성
      const formData = new URLSearchParams();
      formData.append("id", studentId);
      formData.append("name", name);
      formData.append("password", "1234"); // 테스트용
      formData.append("major", major);
      subMajors.forEach(m => formData.append("minors[]", m));
      clubs.forEach(c => formData.append("clubs[]", c));

      // 서버로 전송
      fetch("/signup", {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: formData.toString()
      })
      .then(res => res.json())
      .then(data => {
        if (data.success) {
          alert("회원가입이 완료되었습니다!");
          window.location.href = "login.html";
        } else {
          alert("회원가입 실패: 이미 존재하는 ID입니다.");
        }
      })
      .catch(err => {
        console.error("에러 발생:", err);
        alert("서버 오류가 발생했습니다.");
      });


    });
  });
  