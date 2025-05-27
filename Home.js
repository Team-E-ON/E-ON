const weekDays = ["Mon", "Tue", "Wed", "Thur", "Fri", "Sat", "Sun"];
const calendarHead = document.getElementById("calendar-head");
const calendarBody = document.getElementById("calendar-body");
const calendarTitle = document.getElementById("calendar-title");

// 오늘 날짜 기준
const today = new Date();
const year = today.getFullYear();
const month = today.getMonth(); // 0: Jan, 1: Feb ...
const date = today.getDate();

// 달력 타이틀 표시
const monthNames = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December"
];
calendarTitle.textContent = `${monthNames[month]} ${year}`;

// 헤더 렌더링
weekDays.forEach(day => {
  const th = document.createElement("th");
  th.textContent = day;
  calendarHead.appendChild(th);
});

// 이번 달의 첫째 날과 마지막 날
const firstDay = new Date(year, month, 1);
const lastDay = new Date(year, month + 1, 0);

// 시작 요일 (월요일 기준으로 보정)
let startDay = firstDay.getDay(); // 0 (일) ~ 6 (토)
startDay = (startDay + 6) % 7; // 월요일=0으로 바꾸기

let currentRow = document.createElement("tr");
let cellCount = 0;

// 빈 칸 먼저 추가
for (let i = 0; i < startDay; i++) {
  const td = document.createElement("td");
  td.className = "empty";
  currentRow.appendChild(td);
  cellCount++;
}

// 날짜 채우기
for (let day = 1; day <= lastDay.getDate(); day++) {
  const td = document.createElement("td");
  td.textContent = String(day).padStart(2, "0");

  if (day === date) td.classList.add("today");

  currentRow.appendChild(td);
  cellCount++;

  if (cellCount === 7) {
    calendarBody.appendChild(currentRow);
    currentRow = document.createElement("tr");
    cellCount = 0;
  }
}

// 마지막 줄 남은 칸 채우기
if (cellCount > 0) {
  while (cellCount < 7) {
    const td = document.createElement("td");
    td.className = "empty";
    currentRow.appendChild(td);
    cellCount++;
  }
  calendarBody.appendChild(currentRow);
}

window.addEventListener('DOMContentLoaded', () => {
  const user = getCookie('username');
  const menuRight = document.querySelector('.menu-right');

  if (user) {
    menuRight.innerHTML = `
      <span>${user}</span>
      <span>|</span>
      <a href="#" onclick="logout()">로그아웃</a>
    `;
  }
});

function getCookie(name) {
  const matches = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return matches ? decodeURIComponent(matches[1]) : undefined;
}

function logout() {
  document.cookie = "sessionId=; path=/; max-age=0";
  document.cookie = "username=; path=/; max-age=0";
  location.href = "/login.html";
}
