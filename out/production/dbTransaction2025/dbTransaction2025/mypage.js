const openModalBtn = document.getElementById('pw-btn');
const cancelModalBtn = document.getElementById('cancel-btn');
const modalBg = document.getElementById('modal-bg');

openModalBtn.addEventListener('click', () => {
  modalBg.classList.add('active');
});

cancelModalBtn.addEventListener('click', () => {
  modalBg.classList.remove('active');
});
