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
  
      alert("회원가입이 완료되었습니다!");
      window.location.href = "login.html"; 
    });
  });
  
  window.addEventListener("DOMContentLoaded", () => {
    const user = getCookie("username");
    const menuRight = document.querySelector(".user-menu");

    if (user && menuRight) {
      menuRight.innerHTML = `
        <span>${user}</span>
        <span>|</span>
        <a href="#" onclick="logout()">로그아웃</a>
      `;
    }
  });

  function getCookie(name) {
    const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
    return match ? decodeURIComponent(match[1]) : undefined;
  }

  function logout() {
    document.cookie = "sessionId=; path=/; max-age=0";
    document.cookie = "username=; path=/; max-age=0";
    location.href = "/login.html";
  }

