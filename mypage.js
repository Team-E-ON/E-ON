const openModalBtn = document.getElementById('pw-btn');
const cancelModalBtn = document.getElementById('cancel-btn');
const modalBg = document.getElementById('modal-bg');

openModalBtn.addEventListener('click', () => {
  modalBg.classList.add('active');
});

cancelModalBtn.addEventListener('click', () => {
  modalBg.classList.remove('active');
});


  window.addEventListener("DOMContentLoaded", () => {
    const user = getCookie("username");
    const menuRight = document.querySelector(".mypage-menu-right");

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
